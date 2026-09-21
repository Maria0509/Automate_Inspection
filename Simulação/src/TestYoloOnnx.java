import Utilities.YoloOnnxDetector;

public class TestYoloOnnx {

    public static void main(String[] args) throws Exception {

        YoloOnnxDetector detector = new YoloOnnxDetector(
                "CPS/models/best.onnx",
                640,
                0.30f
        );

        YoloOnnxDetector.InspectionResult result =
                detector.detect("CPS/inspection_images/Qualitycontrol1.jpg");

        System.out.println(result);
    }
}
