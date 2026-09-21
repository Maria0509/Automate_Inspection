package Utilities;


import ai.onnxruntime.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;

public class YoloOnnxDetector {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    private final int imgSize;
    private final float confThreshold;

    public YoloOnnxDetector(String modelPath, int imgSize, float confThreshold) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        this.session = env.createSession(modelPath, options);

        this.inputName = session.getInputNames().iterator().next();
        this.imgSize = imgSize;
        this.confThreshold = confThreshold;

        System.out.println("[YOLO] Model loaded: " + modelPath);
        System.out.println("[YOLO] Input name: " + inputName);
        System.out.println("[YOLO] Inputs: " + session.getInputInfo());
        System.out.println("[YOLO] Outputs: " + session.getOutputInfo());
    }

    public InspectionResult detect(String imagePath) {
        try {
            BufferedImage image = ImageIO.read(new File(imagePath));

            if (image == null) {
                return new InspectionResult("ERROR", 0.0f, -1, "Could not read image");
            }

            OnnxTensor inputTensor = imageToTensor(image);

            OrtSession.Result output = session.run(Collections.singletonMap(inputName, inputTensor));

            Object rawOutput = output.get(0).getValue();

            Detection bestDetection = parseYoloOutput(rawOutput);

            output.close();
            inputTensor.close();

            if (bestDetection == null || bestDetection.confidence < confThreshold) {
                return new InspectionResult("OK", 0.0f, -1, "No defect detected");
            }

            return new InspectionResult(
                    "NOK",
                    bestDetection.confidence,
                    bestDetection.classId,
                    "Defect detected"
            );

        } catch (Exception e) {
            e.printStackTrace();
            return new InspectionResult("ERROR", 0.0f, -1, e.getMessage());
        }
    }

    private OnnxTensor imageToTensor(BufferedImage image) throws OrtException {
        BufferedImage resized = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = resized.createGraphics();
        g.drawImage(image, 0, 0, imgSize, imgSize, null);
        g.dispose();

        float[] chw = new float[1 * 3 * imgSize * imgSize];

        int channelSize = imgSize * imgSize;

        for (int y = 0; y < imgSize; y++) {
            for (int x = 0; x < imgSize; x++) {
                int rgb = resized.getRGB(x, y);

                int r = (rgb >> 16) & 0xFF;
                int gVal = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int index = y * imgSize + x;

                chw[index] = r / 255.0f;
                chw[channelSize + index] = gVal / 255.0f;
                chw[2 * channelSize + index] = b / 255.0f;
            }
        }

        long[] shape = new long[]{1, 3, imgSize, imgSize};

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape);
    }

    private Detection parseYoloOutput(Object rawOutput) {
        Detection best = null;

        System.out.println("[YOLO] Raw output type: " + rawOutput.getClass());

        if (rawOutput instanceof float[][][]) {
            float[][][] output = (float[][][]) rawOutput;

            System.out.println("[YOLO] Output shape approx: [" +
                    output.length + ", " +
                    output[0].length + ", " +
                    output[0][0].length + "]");

            for (int i = 0; i < output[0].length; i++) {
                float[] det = output[0][i];

                if (det.length >= 6) {
                    float conf = det[4];
                    int classId = Math.round(det[5]);

                    if (conf > confThreshold) {
                        if (best == null || conf > best.confidence) {
                            best = new Detection(conf, classId);
                        }
                    }
                }
            }
        } else {
            System.out.println("[YOLO] Unexpected output type: " + rawOutput.getClass());
        }

        return best;
    }

    private static class Detection {
        float confidence;
        int classId;

        Detection(float confidence, int classId) {
            this.confidence = confidence;
            this.classId = classId;
        }
    }

    public static class InspectionResult {
        public final String status;
        public final float confidence;
        public final int classId;
        public final String message;

        public InspectionResult(String status, float confidence, int classId, String message) {
            this.status = status;
            this.confidence = confidence;
            this.classId = classId;
            this.message = message;
        }

        @Override
        public String toString() {
            return "InspectionResult{" +
                    "status='" + status + '\'' +
                    ", confidence=" + confidence +
                    ", classId=" + classId +
                    ", message='" + message + '\'' +
                    '}';
        }
    }
}