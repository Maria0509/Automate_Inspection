from fastapi import FastAPI, UploadFile, File
from ultralytics import YOLO
import shutil
from pathlib import Path
import uuid

app = FastAPI()

model = YOLO("best.pt")


@app.get("/")
def home():
    return {"message": "Inspection API is running"}


@app.post("/inspect")
async def inspect_image(file: UploadFile = File(...)):
    temp_dir = Path("temp")
    temp_dir.mkdir(exist_ok=True)

    image_path = temp_dir / f"{uuid.uuid4()}_{file.filename}"

    with open(image_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    results = model.predict(
        source=str(image_path),
        conf=0.2,
        save=False
    )

    detections = []

    for r in results:
        print("Boxes detected:", len(r.boxes))
        print(r.boxes)
        for box in r.boxes:
            conf = float(box.conf[0])
            x_center, y_center, width, height = box.xywhn[0].tolist()

            detections.append({
                "class_name": "defect",
                "confidence": conf,
                "x_center": x_center,
                "y_center": y_center,
                "width": width,
                "height": height
            })

    if len(detections) == 0:
        return {
            "status": "OK",
            "defects": [],
            "recommended_station": None
        }

    defect = detections[0]

    if defect["x_center"] < 0.5 or defect["y_center"] < 0.5:
        station = "GlueStation1"
    else:
        station = "GlueStation2"

    return {
        "status": "NOK",
        "defects": detections,
        "recommended_station": station
    }