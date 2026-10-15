package com.raunak.wareye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One detection; coordinates are normalized (0..1) to the upright camera frame. */
class Detection(
    val cls: Int,
    val score: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
)

class FrameResult(
    val detections: List<Detection>,
    /** Upright (rotated) analysis frame size, for mapping onto the preview. */
    val frameW: Int, val frameH: Int,
    val preMs: Float, val inferMs: Float, val postMs: Float,
)

/**
 * YOLO26n (raw one-to-many head, no NMS in the model) on LiteRT CompiledModel.
 * Not thread-safe: call [detect] from one thread only (Engine's executor).
 */
class Detector private constructor(
    private val env: Environment,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    val accelerator: String,
    private val inputW: Int,
    private val inputH: Int,
    private val nchw: Boolean,
    /** Output (1, C, N) if true, (1, N, C) if false. */
    private val channelsFirst: Boolean,
    private val numChannels: Int,
    private val numAnchors: Int,
) : AutoCloseable {

    val numClasses = numChannels - 4

    // Reused per-frame buffers.
    private var frameBitmap: Bitmap? = null
    private val inputBitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val pixels = IntArray(inputW * inputH)
    private val inputFloats = FloatArray(3 * inputW * inputH)
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val candKeys = LongArray(numAnchors)
    private val candBox = FloatArray(numAnchors * 4)
    private val candCls = IntArray(numAnchors)
    private val candScore = FloatArray(numAnchors)
    private val keptIdx = IntArray(MAX_DET)
    private var selectedMask = BooleanArray(numClasses)
    private var selectedIds = IntArray(0)

    // Decided from the first real frame and logged.
    private var firstFrame = true
    private var boxesNormalized = true
    private var needsSigmoid = false

    fun setSelected(ids: Set<Int>) {
        selectedMask = BooleanArray(numClasses) { it in ids }
        selectedIds = (0 until numClasses).filter { selectedMask[it] }.toIntArray()
    }

    fun detect(image: ImageProxy, mirror: Boolean, conf: Float, iou: Float): FrameResult {
        val t0 = System.nanoTime()
        val rotation = image.imageInfo.rotationDegrees
        val srcW = image.width
        val srcH = image.height
        val (uw, uh) = if (rotation % 180 == 0) srcW to srcH else srcH to srcW
        val scale = min(inputW.toFloat() / uw, inputH.toFloat() / uh)
        val padX = (inputW - uw * scale) / 2f
        val padY = (inputH - uh * scale) / 2f

        copyFrame(image)
        // Rotate upright, scale to fit, center on a 114-gray canvas (Ultralytics letterbox).
        matrix.reset()
        matrix.postTranslate(-srcW / 2f, -srcH / 2f)
        matrix.postRotate(rotation.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(inputW / 2f, inputH / 2f)
        inputCanvas.drawColor(PAD_COLOR)
        inputCanvas.save()
        inputCanvas.concat(matrix)
        inputCanvas.clipRect(0, 0, srcW, srcH) // hides row-stride padding columns, if any
        inputCanvas.drawBitmap(frameBitmap!!, 0f, 0f, paint)
        inputCanvas.restore()
        inputBitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        fillInput()
        inputs[0].writeFloat(inputFloats)
        val t1 = System.nanoTime()

        model.run(inputs, outputs)
        val out = outputs[0].readFloat() // LiteRT API returns a new array each call.
        val t2 = System.nanoTime()

        if (firstFrame) inspectFirstOutput(out)
        val dets = decode(out, conf, iou, scale, padX, padY, uw, uh, mirror)
        val t3 = System.nanoTime()
        return FrameResult(dets, uw, uh, (t1 - t0) / 1e6f, (t2 - t1) / 1e6f, (t3 - t2) / 1e6f)
    }

    private fun copyFrame(image: ImageProxy) {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        var bmp = frameBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBitmap = bmp
        }
        val src = plane.buffer
        src.rewind()
        val rowBytes = w * 4
        if (plane.rowStride == rowBytes) {
            bmp.copyPixelsFromBuffer(src)
            return
        }
        // Row padding: pack rows into a reused buffer (the last row may be shorter than rowStride).
        var packed = packedRows
        if (packed == null || packed.capacity() != rowBytes * h) {
            packed = ByteBuffer.allocateDirect(rowBytes * h)
            packedRows = packed
        }
        packed!!.clear()
        var row = rowScratch
        if (row == null || row.size != rowBytes) { row = ByteArray(rowBytes); rowScratch = row }
        for (y in 0 until h) {
            src.position(y * plane.rowStride)
            src.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.rewind()
        bmp.copyPixelsFromBuffer(packed)
    }

    private var packedRows: ByteBuffer? = null
    private var rowScratch: ByteArray? = null

    private fun fillInput() {
        val plane = inputW * inputH
        if (nchw) {
            for (i in 0 until plane) {
                val p = pixels[i]
                inputFloats[i] = ((p shr 16) and 0xFF) * INV_255
                inputFloats[i + plane] = ((p shr 8) and 0xFF) * INV_255
                inputFloats[i + 2 * plane] = (p and 0xFF) * INV_255
            }
        } else {
            var j = 0
            for (i in 0 until plane) {
                val p = pixels[i]
                inputFloats[j++] = ((p shr 16) and 0xFF) * INV_255
                inputFloats[j++] = ((p shr 8) and 0xFF) * INV_255
                inputFloats[j++] = (p and 0xFF) * INV_255
            }
        }
    }

    private fun value(out: FloatArray, anchor: Int, channel: Int): Float =
        if (channelsFirst) out[channel * numAnchors + anchor] else out[anchor * numChannels + channel]

    private fun inspectFirstOutput(out: FloatArray) {
        firstFrame = false
        var bMin = Float.MAX_VALUE; var bMax = -Float.MAX_VALUE
        var cMin = Float.MAX_VALUE; var cMax = -Float.MAX_VALUE
        for (a in 0 until numAnchors) {
            for (c in 0 until numChannels) {
                val v = value(out, a, c)
                if (c < 4) { bMin = min(bMin, v); bMax = max(bMax, v) } else { cMin = min(cMin, v); cMax = max(cMax, v) }
            }
        }
        boxesNormalized = bMax <= 2f
        needsSigmoid = cMin < 0f || cMax > 1f
        Log.i(TAG, "first frame: box channels min=%.4f max=%.4f -> %s; class channels min=%.4g max=%.4f -> %s".format(
            bMin, bMax, if (boxesNormalized) "normalized, x$inputW" else "pixels",
            cMin, cMax, if (needsSigmoid) "logits, applying sigmoid" else "already 0..1, no sigmoid"))
    }

    private fun decode(
        out: FloatArray, conf: Float, iou: Float,
        scale: Float, padX: Float, padY: Float, uw: Int, uh: Int, mirror: Boolean,
    ): List<Detection> {
        val ids = selectedIds
        if (ids.isEmpty()) return emptyList()
        // Compare raw values against the threshold in the model's own space.
        val threshold = if (needsSigmoid) ln(conf / (1f - conf)) else conf
        val boxScale = if (boxesNormalized) inputW.toFloat() else 1f
        var n = 0
        for (a in 0 until numAnchors) {
            var best = -Float.MAX_VALUE
            var bestCls = -1
            for (c in ids) { // filter BEFORE NMS: only selected classes compete
                val v = value(out, a, 4 + c)
                if (v > best) { best = v; bestCls = c }
            }
            if (best < threshold) continue
            val score = if (needsSigmoid) 1f / (1f + exp(-best)) else best
            val cx = value(out, a, 0) * boxScale
            val cy = value(out, a, 1) * boxScale
            val w = value(out, a, 2) * boxScale
            val h = value(out, a, 3) * boxScale
            val o = n * 4
            candBox[o] = cx - w / 2; candBox[o + 1] = cy - h / 2
            candBox[o + 2] = cx + w / 2; candBox[o + 3] = cy + h / 2
            candCls[n] = bestCls
            candScore[n] = score
            candKeys[n] = (java.lang.Float.floatToIntBits(score).toLong() shl 32) or n.toLong()
            n++
        }
        java.util.Arrays.sort(candKeys, 0, n) // ascending; positive float bits sort like floats

        // Greedy class-aware NMS, highest score first.
        var kept = 0
        var k = n - 1
        while (k >= 0 && kept < MAX_DET) {
            val i = (candKeys[k] and 0xFFFFFFFFL).toInt()
            k--
            var keep = true
            for (j in 0 until kept) {
                val other = keptIdx[j]
                if (candCls[other] == candCls[i] && iou(i, other) > iou) { keep = false; break }
            }
            if (keep) keptIdx[kept++] = i
        }

        val result = ArrayList<Detection>(kept)
        for (j in 0 until kept) {
            val i = keptIdx[j]
            val o = i * 4
            // Undo letterbox -> upright frame, normalized.
            var x1 = ((candBox[o] - padX) / scale / uw).coerceIn(0f, 1f)
            val y1 = ((candBox[o + 1] - padY) / scale / uh).coerceIn(0f, 1f)
            var x2 = ((candBox[o + 2] - padX) / scale / uw).coerceIn(0f, 1f)
            val y2 = ((candBox[o + 3] - padY) / scale / uh).coerceIn(0f, 1f)
            if (mirror) { val t = 1f - x1; x1 = 1f - x2; x2 = t } // front camera preview is mirrored
            if (x2 - x1 <= 0f || y2 - y1 <= 0f) continue
            result.add(Detection(candCls[i], candScore[i], x1, y1, x2, y2))
        }
        return result
    }

    private fun iou(a: Int, b: Int): Float {
        val oa = a * 4; val ob = b * 4
        val ix = min(candBox[oa + 2], candBox[ob + 2]) - max(candBox[oa], candBox[ob])
        val iy = min(candBox[oa + 3], candBox[ob + 3]) - max(candBox[oa + 1], candBox[ob + 1])
        if (ix <= 0f || iy <= 0f) return 0f
        val inter = ix * iy
        val areaA = (candBox[oa + 2] - candBox[oa]) * (candBox[oa + 3] - candBox[oa + 1])
        val areaB = (candBox[ob + 2] - candBox[ob]) * (candBox[ob + 3] - candBox[ob + 1])
        return inter / (areaA + areaB - inter)
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        env.close()
        frameBitmap?.recycle()
        inputBitmap.recycle()
    }

    companion object {
        const val TAG = "WareyeModel"
        const val MODEL_ASSET = "model.tflite"
        private const val MODEL_CACHE_KEY = "yolo26n_w8a32_v1"
        private const val MAX_DET = 100
        private const val INV_255 = 1f / 255f
        private val PAD_COLOR = Color.rgb(114, 114, 114)

        /**
         * Compiles the model for one accelerator and runs a zero-input warm-up.
         * Throws if compile or warm-up fails; Engine handles the GPU -> CPU ladder.
         */
        fun create(context: Context, gpu: Boolean, onStage: (Int) -> Unit): Detector {
            val env = Environment.create()
            onStage(STAGE_COMPILE) // runtime is up; compiling next
            var model: CompiledModel? = null
            try {
                val options = if (gpu) {
                    CompiledModel.Options(Accelerator.GPU).apply {
                        gpuOptions = CompiledModel.GpuOptions(
                            serializationDir = context.codeCacheDir.absolutePath,
                            modelCacheKey = MODEL_CACHE_KEY,
                            serializeProgramCache = true,
                        )
                    }
                } else {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    CompiledModel.Options(Accelerator.CPU).apply {
                        cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
                    }
                }
                val t0 = System.nanoTime()
                model = CompiledModel.create(context.assets, MODEL_ASSET, options, env)
                val inputs = model.createInputBuffers()
                val outputs = model.createOutputBuffers()
                val compileMs = (System.nanoTime() - t0) / 1e6

                val inDims = tensorDims(model, input = true)
                val outDims = tensorDims(model, input = false)
                val nchw = inDims.size == 4 && inDims[1] == 3
                val inH = if (nchw) inDims[2] else inDims[1]
                val inW = if (nchw) inDims[3] else inDims[2]
                val channelsFirst = outDims[1] < outDims[2]
                val channels = if (channelsFirst) outDims[1] else outDims[2]
                val anchors = if (channelsFirst) outDims[2] else outDims[1]

                // Warm-up with a zero input, still inside the fallback path.
                val t1 = System.nanoTime()
                inputs[0].writeFloat(FloatArray(inDims.fold(1) { a, b -> a * b }))
                model.run(inputs, outputs)
                val warm = outputs[0].readFloat()
                check(warm.size == channels * anchors) { "output size ${warm.size} != $channels x $anchors" }
                val warmMs = (System.nanoTime() - t1) / 1e6

                val acc = if (gpu) "GPU" else "CPU"
                Log.i(TAG, "compiled on $acc in %.0f ms, warm-up run %.0f ms".format(compileMs, warmMs))
                Log.i(TAG, "layout: input %s (%s %dx%d), output %s (%s: %d channels = 4 box + %d classes, %d anchors)".format(
                    inDims.contentToString(), if (nchw) "NCHW" else "NHWC", inW, inH,
                    outDims.contentToString(), if (channelsFirst) "channels-first" else "transposed",
                    channels, channels - 4, anchors))
                return Detector(env, model, inputs, outputs, acc, inW, inH, nchw, channelsFirst, channels, anchors)
            } catch (t: Throwable) {
                model?.close()
                env.close()
                throw t
            }
        }

        const val STAGE_FILE = 0
        const val STAGE_RUNTIME = 1
        const val STAGE_COMPILE = 2
        const val STAGE_CAMERA = 3

        /**
         * Reads the real tensor shape. Names come from the model's serving_default signature
         * (args_0 -> output_0); the full tensor names are tried as a fallback.
         */
        private fun tensorDims(model: CompiledModel, input: Boolean): IntArray {
            val candidates = if (input) {
                listOf("args_0" to "serving_default", "args_0" to "", "serving_default_args_0" to "")
            } else {
                listOf("output_0" to "serving_default", "output_0" to "", "serving_default_output_0_output" to "")
            }
            for ((name, sig) in candidates) {
                val type: TensorType = try {
                    if (input) model.getInputTensorType(name, sig) else model.getOutputTensorType(name, sig)
                } catch (t: Throwable) {
                    continue
                }
                val dims = type.layout?.dimensions?.toIntArray() ?: continue
                Log.i(TAG, "%s tensor '%s' (signature '%s'): shape %s, type %s".format(
                    if (input) "input" else "output", name, sig, dims.contentToString(), type.elementType))
                return dims
            }
            // Documented export shapes, only used if the names above do not resolve.
            val fallback = if (input) intArrayOf(1, 3, 640, 640) else intArrayOf(1, 4 + ClassRegistry.all.size, 8400)
            Log.w(TAG, "could not read ${if (input) "input" else "output"} tensor type; assuming ${fallback.contentToString()}")
            return fallback
        }
    }
}
