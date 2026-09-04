# Wareye
yolo model on mobile app

## 1. Data Annotation Step

End-to-end project: **data → annotation → model training/fine-tuning → deployment on app.** This README covers the data and annotation stage. Detection classes: `plate, spoon, fork, knife, cup, glass`.

- **GitHub:** wareye
- **Kaggle dataset:** https://www.kaggle.com/datasets/raunakgola/kitchenware-object-detection-dataset-yolo-format

## Annotation Pipeline

Two rounds of bootstrap-label-then-review instead of labeling ~9,000 images by hand.

**Round 1 — Seed set (900 images, stratified 150/class)**

| Step | Tool | Time |
|---|---|---|
| Zero-shot auto-label | Grounding DINO | 10 min |
| Review & correct | Custom Streamlit box editor | 1 hr |
| Manual label of gaps | X-AnyLabeling | 25 min |

**Round 2 — Train seed model → auto-label the rest**

Seed set trained a YOLOv8s active-learning model, used only to pre-label the remaining ~8,500 images faster than manual/zero-shot labeling — not the final production model.

| Step | Time |
|---|---|
| Train (70 epochs) + auto-label rest | 36 min (31 min training) |

| Class | mAP50 | mAP50-95 |
|---|---|---|
| all | 0.995 | 0.981 |
| plate / spoon / fork | 0.995 | 0.993 / 0.989 / 0.967 |
| knife / cup / glass | 0.995 | 0.958 / 0.989 / 0.990 |

> Measured on the seed set itself, not a held-out split — an optimistic number, expected here since this model's only job was labeling, not deployment. Every prediction it made was still human-reviewed in Round 3.

**Round 3 — Review active-learning labels (9,445 images)**

| Step | Time |
|---|---|
| Review, correct, remove bad labels | 5 hr |

Some images (e.g. bowls mislabeled as plate) were removed rather than corrected to avoid teaching false visual similarities. **Final: 8,984 images.**

## Final Class Distribution

| Class | Instances | % |
|---|---|---|
| cup | 2,047 | 22.5% |
| plate | 1,801 | 19.8% |
| spoon | 1,658 | 18.2% |
| knife | 1,407 | 15.5% |
| glass | 1,252 | 13.8% |
| fork | 928 | 10.2% |

~2.2× imbalance, fork smallest and most visually confusable with spoon/knife.

## Dataset Structure

```
dataset/
├── train/{images, labels, classes.txt}
└── val/{images, labels, classes.txt}
```

Standard YOLO format: `class_id x_center y_center width height`, normalized 0–1, one `.txt` per image (empty if no objects). Train/val split (85/15) stratified by each image's primary class.

## Time Investment

| Stage | Time |
|---|---|
| Bootstrap + review + gap-fill (seed) | ~1.6 hr |
| Train seed model + auto-label rest | 36 min |
| Review active-learning labels | 5 hr |
| **Total** | **~7 hr** |

## Tools Built

- **Streamlit box editor** — keep/delete boxes, fix wrong class labels, auto-queue unlabeled images, jump-to-image, auto-advance (pauses on multi-object images)
- Stratified sampling, dataset integrity checks, renumbering, and stratified train/val split scripts

## Next Stages

Model training/fine-tuning and mobile app deployment are documented separately (linked from the main repo once published).
