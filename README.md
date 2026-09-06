# Wareye
yolo model on mobile app

## 1. Data Annotation Step

End-to-end project: **data → annotation → model training/fine-tuning → deployment on app.** This README covers the data and annotation stage. Detection classes: `plate, spoon, fork, knife, cup, glass`.

- **GitHub:** wareye
- **Kaggle dataset:** https://www.kaggle.com/datasets/raunakgola/kitchenware-object-detection-dataset-yolo-format

## Annotation Pipeline

Two rounds of bootstrap-label-then-review instead of labeling ~9,000 images by hand.

**Round 1 — Seed set (900 images, stratified 150/class)**

<div align="center">

| Step | Tool | Time |
|---|---|---|
| Zero-shot auto-label | Grounding DINO | 10 min |
| Review & correct | Custom Streamlit box editor | 1 hr |
| Manual label of gaps | X-AnyLabeling | 25 min |

</div>

**Round 2 — Train seed model → auto-label the rest**

Seed set trained a YOLOv8s active-learning model, used only to pre-label the remaining ~8,500 images faster than manual/zero-shot labeling — not the final production model.

<div align="center">

| Step | Time |
|---|---|
| Train (70 epochs) + auto-label rest | 36 min (31 min training) |

| Class | mAP50 | mAP50-95 |
|---|---|---|
| all | 0.995 | 0.981 |
| plate / spoon / fork | 0.995 | 0.993 / 0.989 / 0.967 |
| knife / cup / glass | 0.995 | 0.958 / 0.989 / 0.990 |

</div>

> Measured on the seed set itself, not a held-out split — an optimistic number, expected here since this model's only job was labeling, not deployment. Every prediction it made was still human-reviewed in Round 3.

**Round 3 — Review active-learning labels (9,445 images)**

<div align="center">

| Step | Time |
|---|---|
| Review, correct, remove bad labels | 5 hr |

</div>

Some images (e.g. bowls mislabeled as plate) were removed rather than corrected to avoid teaching false visual similarities. **Final: 8,984 images.**

## Final Class Distribution

<div align="center">

| Class | Instances | % |
|---|---|---|
| cup | 2,047 | 22.5% |
| plate | 1,801 | 19.8% |
| spoon | 1,658 | 18.2% |
| knife | 1,407 | 15.5% |
| glass | 1,252 | 13.8% |
| fork | 928 | 10.2% |

</div>

~2.2× imbalance, fork smallest and most visually confusable with spoon/knife.

## Dataset Structure

```
dataset/
├── train/{images, labels, classes.txt}
└── val/{images, labels, classes.txt}
```

Standard YOLO format: `class_id x_center y_center width height`, normalized 0–1, one `.txt` per image (empty if no objects). Train/val split (85/15) stratified by each image's primary class.

## Time Investment

<div align="center">

| Stage | Time |
|---|---|
| Bootstrap + review + gap-fill (seed) | ~1.6 hr |
| Train seed model + auto-label rest | 36 min |
| Review active-learning labels | 5 hr |
| **Total** | **~7 hr** |

</div>

## Tools Built

- **Streamlit box editor** — keep/delete boxes, fix wrong class labels, auto-queue unlabeled images, jump-to-image, auto-advance (pauses on multi-object images)
- Stratified sampling, dataset integrity checks, renumbering, and stratified train/val split scripts

## Next Stages

Model training/fine-tuning and mobile app deployment are documented separately (linked from the main repo once published).

## 2. Baseline Fine-tuning

Trained YOLOv8l on the final 8,984-image annotated dataset (6 classes: `plate, spoon, fork, knife, cup, glass`) to establish an accuracy reference before any compression (pruning/distillation) work.

- **Model:** YOLOv8l — 43,611,234 parameters, 164.8 GFLOPs, 113 layers (fused)
- **Epochs:** 50
- **Training time:** 4 hr 59 min (4.964 hrs) — **Total notebook runtime:** 5 hr 8 min
- **Hardware/env:** Tesla T4 (Colab, 14913MiB), CUDA 12.8, torch 2.11.0, Ultralytics 8.4.140
- **Logs/artifacts:** `Wareye\Model Training\training_export`

### Final Epoch (50/50)

<div align="center">

| box_loss | cls_loss | dfl_loss | Precision | Recall | mAP50 | mAP50-95 |
|---|---|---|---|---|---|---|
| 0.192 | 0.138 | 0.832 | 0.974 | 0.972 | 0.989 | 0.947 |

</div>

### Validation (best.pt)

<div align="center">

| Metric | Value |
|---|---|
| mAP50 | 0.9892 |
| mAP50-95 | 0.9495 |
| Precision | 0.9810 |
| Recall | 0.9669 |

| Class | Images | Instances | Precision | Recall | mAP50 | mAP50-95 |
|---|---|---|---|---|---|---|
| plate | 356 | 356 | 0.992 | 0.993 | 0.995 | 0.974 |
| spoon | 329 | 330 | 0.991 | 0.979 | 0.994 | 0.964 |
| fork | 187 | 188 | 0.995 | 0.986 | 0.992 | 0.947 |
| knife | 280 | 281 | 0.989 | 0.975 | 0.991 | 0.898 |
| cup | 408 | 411 | 0.979 | 0.944 | 0.990 | 0.969 |
| glass | 250 | 254 | 0.942 | 0.925 | 0.973 | 0.945 |

</div>

> `knife` has the lowest mAP50-95 (0.898) despite a high mAP50 — box localization is less tight than other classes, consistent with a thin/elongated shape making high-IoU boxes harder to hit. `glass` has the lowest precision/recall of the six, likely from visual overlap with `cup`.

### Training Curves & Results

**Loss and metric curves over 50 epochs:**

<p align="center">
  <img src="Model%20Training/training_export/runs/baseline_yolov8l/results.png" alt="Training curves">
</p>

**Normalized confusion matrix** — diagonal near 1.0 across all six classes; `cup`/`glass` show the only real cross-class confusion (0.03 each way):

<p align="center">
  <img src="Model%20Training/training_export/runs/baseline_yolov8l/confusion_matrix_normalized.png" alt="Normalized confusion matrix">
</p>

**Inference speed:** 0.1ms preprocess, 13.8ms inference, 1.1ms postprocess per image (T4, batch 1).

This is the teacher/reference model for the planned pruning + distillation stage — not the final model intended for on-device deployment.
