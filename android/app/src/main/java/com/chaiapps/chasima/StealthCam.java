package com.chaiapps.chasima;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/** צילום חשאי מהמצלמה הקדמית (ללא תצוגה) – לזיהוי מי מחזיק בנגן. */
final class StealthCam {
    private StealthCam() {}

    /** מצלם ושולח להורה. reason: "theft" / "parent". רץ ברקע. */
    static void capture(final Context ctx, final String reason) {
        final Context c = ctx.getApplicationContext();
        if (c.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        final CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            String front = null, back = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id;
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
            }
            final String camId = front != null ? front : back;
            if (camId == null) return;

            final HandlerThread ht = new HandlerThread("cam");
            ht.start();
            final Handler bg = new Handler(ht.getLooper());
            final ImageReader reader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1);

            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    upload(c, bytes, reason);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                    try { reader.close(); } catch (Exception ignored) {}
                    try { ht.quitSafely(); } catch (Exception ignored) {}
                }
            }, bg);

            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    try {
                        final CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        b.addTarget(reader.getSurface());
                        b.set(CaptureRequest.CONTROL_MODE, CameraMetadataAuto());
                        cam.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(CameraCaptureSession s,
                                                        CaptureRequest req, android.hardware.camera2.TotalCaptureResult res) {
                                                    bg.postDelayed(cam::close, 600);
                                                }
                                            }, bg);
                                        } catch (CameraAccessException e) {
                                            cam.close();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        cam.close();
                                    }
                                }, bg);
                    } catch (Exception e) {
                        cam.close();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    cam.close();
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    cam.close();
                }
            }, bg);
        } catch (Exception ignored) {}
    }

    private static int CameraMetadataAuto() {
        return android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO;
    }

    private static void upload(Context c, byte[] jpeg, String reason) {
        try {
            // דחיסה לתמונה קטנה כדי לשלוח במהירות
            Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bm == null) return;
            // המצלמה הקדמית – היפוך אופקי כדי שלא יהיה מראה
            Matrix m = new Matrix();
            m.preScale(-1, 1);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, false);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 55, o);
            String b64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            JSONObject p = new JSONObject();
            p.put("img", b64);
            p.put("r", reason);
            p.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.put("d/" + Cfg.deviceId(c) + "/photo", p.toString());
        } catch (Exception ignored) {}
    }
}
