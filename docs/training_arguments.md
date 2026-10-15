# Training Arguments Explained

What every argument used in this project does, and the value used in each stage. Final-training values are the ones in the executed notebook.

For the ideas behind the main knobs (mosaic, mixup, weight decay, cosine schedule) with plots, and interview-style notes, see [`training_concepts.md`](training_concepts.md).

<div align="center">

| Argument | What it does | Optuna screening | Final training |
|---|---|---|---|
| `epochs` | Maximum number of passes over the training set | 15 | 150 |
| `patience` | Stops training if validation fitness has not improved for this many epochs ([concept](training_concepts.md#early-stopping-patience-and-overfitting)) | 15 | 20 |
| `batch` | Images per step. yolo26n and yolo26s used different sizes because the larger model needs more VRAM per image ([concept](training_concepts.md#batch-size)) | 8, 16 or 32 (searched) | 64 (n), 48 (s) |
| `imgsz` | Training and validation image size | 640 | 640 |
| `optimizer` | Update rule for the weights ([concept](training_concepts.md#adamw-and-the-learning-rate)) | AdamW | AdamW |
| `lr0` | Initial learning rate ([concept](training_concepts.md#cosine-learning-rate-schedule)) | 1e-4 to 1e-2 (searched, log scale) | 0.0002 (n), 0.000218 (s) |
| `cos_lr` | Decays the learning rate along a cosine curve instead of a straight line ([concept](training_concepts.md#cosine-learning-rate-schedule)) | off (default) | on |
| `weight_decay` | Penalty on large weights, reduces overfitting ([concept](training_concepts.md#weight-decay)) | default | 0.0005 |
| `mosaic` | Probability of tiling 4 training images into one. Good for small objects and varied scenes ([concept](training_concepts.md#mosaic-augmentation)) | 0.0 to 1.0 (searched) | 0.52 (n), 0.238 (s) |
| `close_mosaic` | Turns mosaic off for the last N epochs so the model finishes on normal, undistorted images ([concept](training_concepts.md#mosaic-augmentation)) | 10 (default) | 12 (planned, but never reached: runs stopped at epoch 79 and 80, before epoch 138) |
| `mixup` | Probability of blending two images and their labels ([concept](training_concepts.md#mixup)) | 0 (default) | 0.1 |
| `save_period` | Saves a checkpoint every N epochs, so a crashed pod does not lose everything | off | 10 |
| `cache` | Keeps decoded images in `ram` or on `disk` so epochs do not re-decode JPEGs | disk | ram |
| `workers` | Data-loading processes | 12 | 12 |
| `plots` | Writes curves, confusion matrix and sample batches at the end | off | on |

</div>

## Notes

- **`nbs`:** Ultralytics uses a nominal batch size of 64 for its gradient accumulation and weight-decay scaling, so changing `batch` also changes the effective regularization slightly.
- **Mosaic in the Optuna search:** with 15 epochs and the default `close_mosaic=10`, mosaic was only active in the first 5 epochs of each trial. The searched `mosaic` value therefore only affected the early part of training.
- **Search vs final batch size:** the search used batch sizes up to 32, while the final runs used 64 and 48, so the best Optuna `lr0` values are a starting point, not a result tuned for those batch sizes.
- **Metric used everywhere:** mAP50-95 on the validation set (`metrics/mAP50-95(B)`), averaged over IoU thresholds 0.50 to 0.95. It rewards tight boxes, so it is stricter than mAP50.

More on why these settings work, and what to change next time: [`training_concepts.md`](training_concepts.md).
