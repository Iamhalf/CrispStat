package com.crispstat.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    // ---- Tuning: change these if you want different quality/size ----
    /** 720p setting: shortest side in pixels and bitrate (bits per second). */
    private static final int SHORT_SIDE_720 = 720;
    private static final int BITRATE_720 = 2_500_000;
    /** 1080p setting. */
    private static final int SHORT_SIDE_1080 = 1080;
    private static final int BITRATE_1080 = 5_000_000;
    /** Longest side of the output photo, in pixels. */
    private static final int PHOTO_LONG_SIDE = 1920;
    /** JPEG quality for photos (0-100). */
    private static final int PHOTO_QUALITY = 92;
    // ------------------------------------------------------------------

    private Button pickButton;
    private Button shareButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private RadioGroup partLength;
    private RadioGroup quality;
    private int videoBitrate;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private final ArrayList<Uri> outputUris = new ArrayList<>();
    private String outputMime = "video/mp4";

    // Video job state
    private Uri videoSource;
    private long videoDurationMs;
    private long partMs;
    private int totalParts;
    private int currentPart;
    private int targetHeight; // 0 = keep original size
    private Transformer transformer;
    private final ProgressHolder progressHolder = new ProgressHolder();

    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pickButton = findViewById(R.id.pickButton);
        shareButton = findViewById(R.id.shareButton);
        progressBar = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        partLength = findViewById(R.id.partLength);
        quality = findViewById(R.id.quality);

        picker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> { if (uri != null) handlePicked(uri); });

        pickButton.setOnClickListener(v -> picker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build()));

        shareButton.setOnClickListener(v -> shareToWhatsApp());

        // Opened via "Share" from the Gallery
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri shared = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (shared != null) handlePicked(shared);
        }
    }

    private void handlePicked(Uri uri) {
        String type = getContentResolver().getType(uri);
        clearOutputs();
        if (type != null && type.startsWith("image/")) {
            processPhoto(uri);
        } else {
            processVideo(uri);
        }
    }

    private void clearOutputs() {
        outputUris.clear();
        shareButton.setEnabled(false);
        File dir = outDir();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private File outDir() {
        File dir = new File(getCacheDir(), "out");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) progressBar.setProgress(0);
    }

    // ------------------------------------------------------------------
    // Photos
    // ------------------------------------------------------------------

    private void processPhoto(Uri uri) {
        setBusy(true);
        progressBar.setIndeterminate(true);
        statusText.setText("Preparing photo…");
        background.execute(() -> {
            try {
                Bitmap bmp = decodeScaled(uri, PHOTO_LONG_SIDE);
                File out = new File(outDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                try (OutputStream os = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, os);
                }
                int w = bmp.getWidth(), h = bmp.getHeight();
                bmp.recycle();
                Uri shareUri = toShareUri(out);
                handler.post(() -> {
                    progressBar.setIndeterminate(false);
                    setBusy(false);
                    outputMime = "image/jpeg";
                    outputUris.add(shareUri);
                    shareButton.setEnabled(true);
                    statusText.setText("Photo ready (" + w + "×" + h + ", "
                            + humanSize(out.length()) + "). Tap Share to WhatsApp.");
                });
            } catch (Exception e) {
                handler.post(() -> {
                    progressBar.setIndeterminate(false);
                    setBusy(false);
                    statusText.setText("Couldn't process this photo: " + e.getMessage());
                });
            }
        });
    }

    private Bitmap decodeScaled(Uri uri, int maxLongSide) throws Exception {
        Bitmap bmp;
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder respects the photo's rotation automatically
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                int longSide = Math.max(w, h);
                if (longSide > maxLongSide) {
                    float scale = (float) maxLongSide / longSide;
                    decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(is, null, opts);
            }
            if (raw == null) throw new Exception("unsupported image");
            int longSide = Math.max(raw.getWidth(), raw.getHeight());
            if (longSide > maxLongSide) {
                float scale = (float) maxLongSide / longSide;
                bmp = Bitmap.createScaledBitmap(raw,
                        Math.round(raw.getWidth() * scale), Math.round(raw.getHeight() * scale), true);
                raw.recycle();
            } else {
                bmp = raw;
            }
        }
        return bmp;
    }

    // ------------------------------------------------------------------
    // Videos
    // ------------------------------------------------------------------

    private void processVideo(Uri uri) {
        int width, height, rotation;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            videoDurationMs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            width = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rotation = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        } catch (Exception e) {
            statusText.setText("Couldn't read this video: " + e.getMessage());
            return;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        if (videoDurationMs <= 0 || width <= 0 || height <= 0) {
            statusText.setText("Couldn't read this video.");
            return;
        }

        // Size as it appears on screen (after rotation)
        int dispW = (rotation == 90 || rotation == 270) ? height : width;
        int dispH = (rotation == 90 || rotation == 270) ? width : height;
        boolean hd = quality.getCheckedRadioButtonId() == R.id.q1080;
        int maxShort = hd ? SHORT_SIDE_1080 : SHORT_SIDE_720;
        videoBitrate = hd ? BITRATE_1080 : BITRATE_720;
        int shortSide = Math.min(dispW, dispH);
        if (shortSide > maxShort) {
            if (dispH <= dispW) {
                targetHeight = maxShort;               // landscape
            } else {
                targetHeight = Math.round((float) maxShort * dispH / dispW); // portrait
            }
            targetHeight = targetHeight / 2 * 2; // encoders need even sizes
        } else {
            targetHeight = 0; // already small enough, don't upscale
        }

        partMs = partLength.getCheckedRadioButtonId() == R.id.len60 ? 60_000 : 30_000;
        totalParts = (int) Math.max(1, (videoDurationMs + partMs - 1) / partMs);
        // Avoid a tiny last part (< 1 s)
        if (totalParts > 1 && videoDurationMs - (long) (totalParts - 1) * partMs < 1000) totalParts--;

        videoSource = uri;
        currentPart = 0;
        outputMime = "video/mp4";
        setBusy(true);
        exportNextPart();
    }

    private void exportNextPart() {
        if (currentPart >= totalParts) {
            setBusy(false);
            shareButton.setEnabled(true);
            statusText.setText(totalParts == 1
                    ? "Video ready (" + humanSize(totalOutputSize()) + "). Tap Share to WhatsApp."
                    : totalParts + " parts ready (" + humanSize(totalOutputSize())
                      + " total). Tap Share to WhatsApp — they'll post in order.");
            return;
        }

        long start = currentPart * partMs;
        long end = (currentPart == totalParts - 1) ? videoDurationMs : start + partMs;

        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(videoSource);
        if (totalParts > 1) {
            itemBuilder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(start)
                    .setEndPositionMs(end)
                    .build());
        }

        EditedMediaItem.Builder edited = new EditedMediaItem.Builder(itemBuilder.build());
        if (targetHeight > 0) {
            edited.setEffects(new Effects(
                    Collections.emptyList(),
                    Collections.singletonList(Presentation.createForHeight(targetHeight))));
        }

        File out = new File(outDir(), String.format("status_part%02d.mp4", currentPart + 1));

        transformer = new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                .setBitrate(videoBitrate)
                                .build())
                        .build())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition,
                                            @NonNull ExportResult result) {
                        handler.removeCallbacks(progressPoller);
                        outputUris.add(toShareUri(out));
                        currentPart++;
                        exportNextPart();
                    }

                    @Override
                    public void onError(@NonNull Composition composition,
                                        @NonNull ExportResult result,
                                        @NonNull ExportException e) {
                        handler.removeCallbacks(progressPoller);
                        setBusy(false);
                        statusText.setText("Couldn't convert the video: " + e.getMessage());
                    }
                })
                .build();

        statusText.setText(totalParts == 1
                ? "Converting video…"
                : "Converting part " + (currentPart + 1) + " of " + totalParts + "…");
        transformer.start(edited.build(), out.getAbsolutePath());
        handler.post(progressPoller);
    }

    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (transformer == null) return;
            if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                int overall = (int) ((currentPart * 100L + progressHolder.progress) / totalParts);
                progressBar.setProgress(overall);
            }
            handler.postDelayed(this, 300);
        }
    };

    // ------------------------------------------------------------------
    // Sharing
    // ------------------------------------------------------------------

    private void shareToWhatsApp() {
        if (outputUris.isEmpty()) return;
        Intent send;
        if (outputUris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, outputUris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, outputUris);
        }
        send.setType(outputMime);
        ClipData clip = ClipData.newRawUri("", outputUris.get(0));
        for (int i = 1; i < outputUris.size(); i++) clip.addItem(new ClipData.Item(outputUris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                send.setPackage(pkg);
                startActivity(send);
                return;
            } catch (ActivityNotFoundException ignored) {
                // try the next one
            }
        }
        send.setPackage(null);
        Toast.makeText(this, "WhatsApp not found — pick an app", Toast.LENGTH_SHORT).show();
        startActivity(Intent.createChooser(send, "Share to"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Uri toShareUri(File f) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    }

    private long totalOutputSize() {
        long sum = 0;
        File[] files = outDir().listFiles();
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressPoller);
        if (transformer != null) transformer.cancel();
        background.shutdown();
        super.onDestroy();
    }
}
