import os
import glob
import shutil
from collections import Counter
from sklearn.model_selection import train_test_split

SRC_IMAGES_DIR = r"Projects\kitchenware-classification\images"
SRC_LABELS_DIR = r"Projects\all_labels_outputs\predicted_labels"
DEST_DIR = r"Projects\Dataset"

CLASSES = ["plate", "spoon", "fork", "knife", "cup", "glass"]
VAL_RATIO = 0.2
SEED = 42

def get_primary_class(label_path):
    counts = Counter()
    with open(label_path, "r") as f:
        for line in f:
            parts = line.strip().split()
            if parts:
                counts[int(parts[0])] += 1
    return counts.most_common(1)[0][0] if counts else None

def find_image_path(img_id, images_dir):
    matches = glob.glob(os.path.join(images_dir, f"{img_id}.*"))
    return matches[0] if matches else None

label_files = [f for f in os.listdir(SRC_LABELS_DIR) if f.endswith(".txt")]

ids, labels = [], []
for fname in label_files:
    img_id = os.path.splitext(fname)[0]
    primary = get_primary_class(os.path.join(SRC_LABELS_DIR, fname))
    if primary is None:
        continue
    ids.append(img_id)
    labels.append(primary)

train_ids, val_ids = train_test_split(
    ids, test_size=VAL_RATIO, stratify=labels, random_state=SEED
)

print(f"Total train: {len(train_ids)} | Total val: {len(val_ids)}")

def copy_split(ids, split_name):
    img_out = os.path.join(DEST_DIR, split_name, "images")
    lbl_out = os.path.join(DEST_DIR, split_name, "labels")
    os.makedirs(img_out, exist_ok=True)
    os.makedirs(lbl_out, exist_ok=True)

    for img_id in ids:
        src_img = find_image_path(img_id, SRC_IMAGES_DIR)
        src_lbl = os.path.join(SRC_LABELS_DIR, f"{img_id}.txt")
        shutil.copy2(src_img, os.path.join(img_out, os.path.basename(src_img)))
        shutil.copy2(src_lbl, os.path.join(lbl_out, f"{img_id}.txt"))

    with open(os.path.join(DEST_DIR, split_name, "classes.txt"), "w") as f:
        f.write("\n".join(CLASSES) + "\n")

copy_split(train_ids, "train")
copy_split(val_ids, "val")

print(f"Done. Dataset written to {DEST_DIR}")