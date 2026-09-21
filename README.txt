This project is an adapted version of the previous JADE/CoppeliaSim architecture.

Main change:
The YOLO model is no longer called through a FastAPI server. The trained model was exported to ONNX format and is executed directly inside the Java ResourceAgent using ONNX Runtime.

Important files:
- CPS/models/best.onnx: exported YOLO model
- CPS/lib/onnxruntime-1.13.1.jar: ONNX Runtime Java library
- CPS/src/Utilities/YoloOnnxDetector.java: Java class responsible for loading the ONNX model and running inference
- CPS/src/Resource/ResourceAgent.java: Resource agent adapted to call the detector when executing sk_q_c
- CPS/inspection_images/Qualitycontrol1.jpg: sample image used for testing

Test:
The class TestYoloOnnx.java can be executed to test the ONNX model independently from the full JADE system.
