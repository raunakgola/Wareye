import os
import glob
import shutil
import time
import streamlit as st
from PIL import Image, ImageDraw, ImageFont

st.set_page_config(page_title="Dataset Bounding Box Editor", layout="wide")

CLASS_COLORS = [
    "#FF0033",
    "#00CC44",
    "#3388FF",
    "#FF9900",
    "#9933FF",
    "#00CCCC",
]

CLASSES = ["plate", "spoon", "fork", "knife", "cup", "glass"]


def load_yolo_labels(label_path, img_width, img_height):
    # normalized yolo txt (cx, cy, w, h) -> pixel box coords
    if not os.path.exists(label_path):
        return None

    with open(label_path, "r") as f:
        lines = f.readlines()

    annotations = []
    for idx, line in enumerate(lines):
        parts = line.strip().split()
        if len(parts) < 5:
            continue

        cls_id = int(parts[0])
        cx, cy, w, h = map(float, parts[1:5])

        bw = w * img_width
        bh = h * img_height
        xmin = (cx * img_width) - (bw / 2.0)
        ymin = (cy * img_height) - (bh / 2.0)
        xmax = xmin + bw
        ymax = ymin + bh

        annotations.append({
            "id": idx,
            "class_id": cls_id,
            "class_name": CLASSES[cls_id] if cls_id < len(CLASSES) else f"Class_{cls_id}",
            "cx": cx, "cy": cy, "w": w, "h": h,
            "bbox": [xmin, ymin, xmax, ymax]
        })
    return annotations


def save_yolo_labels(label_path, remaining_annotations):
    with open(label_path, "w") as f:
        for ann in remaining_annotations:
            f.write(f"{ann['class_id']} {ann['cx']:.6f} {ann['cy']:.6f} {ann['w']:.6f} {ann['h']:.6f}\n")


def draw_boxes_on_image(image, annotations, selected_ids):
    annotated_img = image.copy()
    draw = ImageDraw.Draw(annotated_img)

    for ann in annotations:
        if ann["id"] not in selected_ids:
            continue
        color = CLASS_COLORS[ann["class_id"] % len(CLASS_COLORS)]
        xmin, ymin, xmax, ymax = ann["bbox"]
        draw.rectangle([xmin, ymin, xmax, ymax], outline=color, width=4)

        text = ann["class_name"]
        draw.rectangle([xmin, max(0, ymin - 20), xmin + (len(text) * 10), max(0, ymin)], fill=color)
        draw.text((xmin + 4, max(0, ymin - 18)), text, fill="white")

    return annotated_img


def advance_to_next_image():
    # no label file, or an empty one -> copy (not move) to the review folder before moving on
    needs_review = (not labels_exist) or (annotations is not None and len(annotations) == 0)
    if needs_review:
        os.makedirs(no_label_dest_dir, exist_ok=True)
        shutil.copy2(current_img_path, os.path.join(no_label_dest_dir, img_name))
        st.toast(f"Copied {img_name} to review folder (no/empty label).")
    st.session_state["img_idx"] = (st.session_state["img_idx"] + 1) % len(image_paths)


st.sidebar.header("Directory Config")
img_dir = st.sidebar.text_input("Image Files Folder Path", value="/content/kitchenware-classification/images")
lbl_dir = st.sidebar.text_input("Labels Files Folder Path", value="/content/autolabeled/labels")
no_label_dest_dir = st.sidebar.text_input("No-Label / Empty-Label Destination Folder", value="/content/needs_review")

if os.path.exists(img_dir):
    image_paths = sorted(glob.glob(os.path.join(img_dir, "*.jpg")) + glob.glob(os.path.join(img_dir, "*.png")))
else:
    image_paths = []

if not image_paths:
    st.sidebar.warning("No images found in the specified directory.")
    st.stop()

if "img_idx" not in st.session_state:
    st.session_state["img_idx"] = 0
if "auto_play" not in st.session_state:
    st.session_state["auto_play"] = False

if st.session_state["img_idx"] >= len(image_paths):
    st.session_state["img_idx"] = 0

current_img_path = image_paths[st.session_state["img_idx"]]
img_name = os.path.basename(current_img_path)
img_id = os.path.splitext(img_name)[0]
current_lbl_path = os.path.join(lbl_dir, f"{img_id}.txt")

col_left, col_center, col_right = st.columns([1, 2.2, 1.8])

with col_center:
    st.caption("Class Legend:")
    tags_html = "".join([
        f'<span style="background-color:{CLASS_COLORS[i % len(CLASS_COLORS)]}; padding: 4px 8px; margin-right: 6px; border-radius: 4px; color: white; font-weight: bold;">{cls}</span>'
        for i, cls in enumerate(CLASSES)
    ])
    st.markdown(tags_html, unsafe_allow_html=True)
    st.write("")

try:
    pil_img = Image.open(current_img_path).convert("RGB")
    img_w, img_h = pil_img.size
except Exception:
    st.error(f"Error loading image: {current_img_path}")
    st.stop()

labels_exist = os.path.exists(current_lbl_path)
annotations = load_yolo_labels(current_lbl_path, img_w, img_h) if labels_exist else None

selected_annotation_ids = []

with col_right:
    st.subheader(f"Coordinates & Labels ({img_name})")

    # fixed height so button positions don't jump around when an image has more/fewer boxes
    with st.container(height=250, border=True):
        if not labels_exist or annotations is None:
            st.error(f"Label file `{img_id}.txt` does not exist!")
        elif len(annotations) == 0:
            st.warning(f"Label file `{img_id}.txt` is empty.")
        else:
            st.write("Select boxes to keep:")
            for ann in annotations:
                box_str = f"[{ann['class_name']}] cx:{ann['cx']:.2f}, cy:{ann['cy']:.2f}, w:{ann['w']:.2f}, h:{ann['h']:.2f}"
                is_checked = st.checkbox(box_str, value=True, key=f"cb_{img_id}_{ann['id']}")
                if is_checked:
                    selected_annotation_ids.append(ann["id"])

    st.write("---")

    if st.button("Delete Unselected Labels", type="secondary", use_container_width=True):
        if labels_exist and annotations:
            kept = [a for a in annotations if a["id"] in selected_annotation_ids]
            save_yolo_labels(current_lbl_path, kept)
            st.success("Updated label file successfully!")
            st.rerun()

    st.write("---")
    st.write("Navigate:")

    col_prev, col_next = st.columns(2)
    with col_prev:
        if st.button("Previous", use_container_width=True):
            st.session_state["img_idx"] = (st.session_state["img_idx"] - 1) % len(image_paths)
            st.rerun()
    with col_next:
        if st.button("Next", use_container_width=True):
            advance_to_next_image()
            st.rerun()

    st.write("---")

    if st.button("Delete Image & Label (Wrong Labels)", type="primary", use_container_width=True):
        if os.path.exists(current_img_path):
            os.remove(current_img_path)
        if os.path.exists(current_lbl_path):
            os.remove(current_lbl_path)
        st.warning(f"Deleted {img_name} and its corresponding label!")
        st.rerun()

    st.write("---")
    st.write("Auto-Advance (every 1.5s):")

    col_auto_start, col_auto_stop = st.columns(2)
    with col_auto_start:
        if st.button("Start", use_container_width=True, disabled=st.session_state["auto_play"]):
            st.session_state["auto_play"] = True
            st.rerun()
    with col_auto_stop:
        if st.button("Stop", use_container_width=True, disabled=not st.session_state["auto_play"]):
            st.session_state["auto_play"] = False
            st.rerun()

    if st.session_state["auto_play"]:
        st.caption("Auto-advancing every 1.5s, click Stop to pause.")

with col_center:
    if labels_exist and annotations and len(annotations) > 0:
        annotated_image = draw_boxes_on_image(pil_img, annotations, selected_annotation_ids)
        st.image(annotated_image, caption=f"Image: {img_name} ({st.session_state['img_idx'] + 1}/{len(image_paths)})", use_container_width=True)
    else:
        st.image(pil_img, caption=f"Image: {img_name} (No Bounding Boxes)", use_container_width=True)

# TODO: this blocks the whole script while sleeping so Stop can lag ~1.5s, fine for now
if st.session_state["auto_play"]:
    time.sleep(1.5)
    advance_to_next_image()
    st.rerun()