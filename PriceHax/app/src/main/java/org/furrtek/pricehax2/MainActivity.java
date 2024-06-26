package org.furrtek.pricehax2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.sourceforge.zbar.Config;
import net.sourceforge.zbar.Image;
import net.sourceforge.zbar.ImageScanner;
import net.sourceforge.zbar.Symbol;
import org.furrtek.pricehax2.databinding.ActivityMainBinding;
import org.jetbrains.annotations.NotNull;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private Handler autoFocusHandler;
    CoordinatorLayout mainlayout;
    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private SerialInputOutputManager usbIoManager;
    FrameLayout preview;
    ImageScanner scanner;

    private long lastPLID = 0;
    String lastBarcodeString = "";
    int imageScale = 100;
    DMImage dmImage = null;
    boolean inBWR = false;
    boolean dithering = false;
    private boolean blasterConnected = false;
    char blasterHWVersion;
    int blasterFWVersion;
    Uri selectedImageUri = null;
    int dispDurationIdx, dispPage;
    boolean transmitting = false;
    boolean loopTX = false;
    boolean autoTX = false;

    static {
        System.loadLibrary("iconv");
    }

    @Override
    public void onResume() {
        super.onResume();
        testConnect();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        mainlayout = binding.coordinatorLayout;    //(CoordinatorLayout) findViewById(R.id.coordinatorLayout);

        // App e-stop (disabled for now, not very useful)
        binding.main.fab.setOnClickListener(view -> {
            finishAffinity();
            System.exit(0);
        });

        // Stop TX
        binding.main.buttonTXStop.setOnClickListener(view -> {
            if (transmitting) {
                // Tell blaster to stop TX
                byte[] data = {(byte)'S'};
                try {
                    usbSerialPort.write(data, 1000);
                } catch(IOException ignore) {
                }
                binding.main.switchLoopTX.setChecked(false);    // Disable loop mode
            }
        });

        // Populate duration spinner
        Spinner spinner = binding.main.spinnerDuration;
        List<String> arrayList = Arrays.asList("2s", "15s", "15m", "Forever");
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, arrayList);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                dispDurationIdx = position;
            }
            @Override
            public void onNothingSelected(AdapterView <?> parent) {
            }
        });

        // Populate page spinner
        List<String> pageList = new ArrayList<>();
        for (int i = 0; i < 7; i++)
            pageList.add(Integer.toString(i));
        ArrayAdapter<String> arrayAdapterPage = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pageList);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.main.spinnerPage.setAdapter(arrayAdapterPage);
        binding.main.spinnerPage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                dispPage = Integer.parseInt(parent.getItemAtPosition(position).toString());
            }
            @Override
            public void onNothingSelected(AdapterView <?> parent) {
            }
        });

        binding.main.buttonTXPageDM.setOnClickListener(view -> {
            // 0x85, 0x00, 0x00, 0x00, 0x00, 0x06, 0xF1, 0x00, 0x00, 0x00, 0x00
            List<IRFrame> frames = new ArrayList<>();
            Byte[] payload = {0x06, 0x00, 0x00, 0x00, 0x00, 0x00};
            payload[1] = (byte)(((dispPage & 7) << 3) | 1);

            if (dispDurationIdx < 3) {
                // Warning: This must match contents of spinnerDuration !
                int[] durations = {2, 15, 15*60};
                int duration = durations[dispDurationIdx];
                payload[4] = (byte)(duration >> 8);
                payload[5] = (byte)(duration & 255);
            } else {
                payload[1] = (byte)(payload[1] | 0x80);   // "Forever" flag
            }

            frames.add(new IRFrame(0, (byte)0x85, Arrays.asList(payload), 30, 200));
            IRTransmit(frames);
        });

        binding.main.buttonTXPage.setOnClickListener(view -> {
            // 0x84, 0x00, 0x00, 0x00, 0x00, 0xAB, 0x00, 0x00, 0x00
            List<IRFrame> frames = new ArrayList<>();
            Byte[] payload = {(byte)0xAB, 0x00, 0x00, 0x00};

            // Warning: This must match contents of spinnerDuration !
            int[] durations = {1, 3, 5, 0x80};
            int duration = durations[dispDurationIdx];
            payload[1] = (byte)(((dispPage & 7) << 3) | duration);

            frames.add(new IRFrame(0, (byte)0x84, Arrays.asList(payload), 30, 200));
            IRTransmit(frames);
        });

        // Loop mode checkbox
        binding.main.switchLoopTX.setOnCheckedChangeListener((buttonView, isChecked) -> loopTX = isChecked);

        // Auto mode checkbox
        binding.main.switchTXAuto.setOnCheckedChangeListener((buttonView, isChecked) -> autoTX = isChecked);

        binding.main.buttonTXImage.setOnClickListener(view -> TransmitImage());

        // Image selection and processing
        ActivityResultLauncher<Intent> someActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        if (result.getData() != null) {
                            selectedImageUri = result.getData().getData();
                            convertImage();
                        }
                    }
                });

        binding.main.buttonLoadImg.setOnClickListener(view -> {
            Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
            photoPickerIntent.setType("image/*");
            photoPickerIntent.setAction(Intent.ACTION_GET_CONTENT);
            someActivityResultLauncher.launch(photoPickerIntent);
        });

        // BW or BWR mode selection
        binding.main.radiogroup.setOnCheckedChangeListener((group, checkedId) -> {
            inBWR = binding.main.radioBWR.isChecked();
            updateImagePreview();
        });

        // Dithering selection
        binding.main.radiogroup2.setOnCheckedChangeListener((group, checkedId) -> {
            dithering = binding.main.radioDitherOn.isChecked();
            convertImage();
        });

        // Image scaling change
        binding.main.seekScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekbar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekbar) {
                imageScale = seekbar.getProgress();
                convertImage();
            }
        });

        // Start camera preview. Ask for camera permission if not already granted.
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(MainActivity.this, new String[] {Manifest.permission.CAMERA}, 123);
        } else {
            setScanPreview();
        }

        // Register USB action receiver
        BroadcastReceiver USBReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    setDisconnected();
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    testConnect();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(USBReceiver, filter);

        // Load default image
        selectedImageUri = Uri.parse("android.resource://org.furrtek.pricehax2/" + R.drawable.dm_128x64);
        convertImage();
    }

    private void TransmitImage() {
        List<IRFrame> frames = DMGen.DMGenFrames(dmImage, inBWR, lastPLID, dispPage);
        IRTransmit(frames);
    }

    private void convertImage() {
        if (selectedImageUri == null) return;
        try {
            Bitmap selectedImage = BitmapFactory.decodeStream(getContentResolver().openInputStream(selectedImageUri));
            selectedImage = scaleImage(selectedImage);

            new DMConvert(
                binding.main.progressbar,
                    dmimage -> {
                        dmImage = dmimage;
                        binding.main.textScale.setText(getString(R.string.image_dimensions, dmimage.bitmapBW.getWidth(), dmimage.bitmapBW.getHeight()));
                        updateImagePreview();
                    }).execute(new DMConvertParams(selectedImage, dithering));
        } catch (Exception e) {
            Log.d("PHX", e.getLocalizedMessage());
            Log.d("PHX", "TEST !!!!");
        }
    }
    public void updateImagePreview() {
        if (dmImage != null)
            binding.main.imageviewDm.setImageBitmap(inBWR ? dmImage.bitmapBWR : dmImage.bitmapBW);
    }

    private Bitmap scaleImage(Bitmap selectedImage) {
        int newWidth, newHeight;
        int originalWidth = selectedImage.getWidth();
        int originalHeight = selectedImage.getHeight();

        if ((originalWidth < 300) && (originalHeight < 300)) {
            // If the original dimensions can plausibly fit in an existing ESL display, keep them
            newWidth = originalWidth;
            newHeight = originalHeight;
            binding.main.seekScale.setEnabled(false);
        } else {
            // Otherwise, use the slider scaling factor, with a resulting width of 300px when it's at max
            // Kind of silly and won't work well if image is taller than it is wide, but good enough for now
            newWidth = (300 * imageScale) / 100;
            newHeight = (newWidth * (originalHeight / originalWidth));
            binding.main.seekScale.setEnabled(true);
        }

        // Perform the slightest possible resize to match the constraint of having a pixel count multiple of 8
        int pixelCount = newWidth * newHeight;
        boolean multipleOfEight = (pixelCount & 7) == 0;
        Log.d("PHX", String.format("pixelCount = %d (%smultiple of 8)", pixelCount, multipleOfEight ? "" : "not a "));
        if (!multipleOfEight) {
            binding.main.textStatus2.setText("Adjusted image size for pixel count to be a multiple of 8.");
            int nearestWidth = newWidth % 8;
            int nearestHeight = newHeight % 8;
            if (nearestWidth < nearestHeight) {
                newWidth -= nearestWidth;
            } else {
                newHeight -= nearestHeight;
            }
            pixelCount = newWidth * newHeight;
            Log.d("PHX", String.format("New pixelCount = %d (multiple of 8)", pixelCount));
        }

        return Bitmap.createScaledBitmap(selectedImage, newWidth, newHeight, true);
    }

    public void IRTransmit(List<IRFrame> frames) {
        if (!blasterConnected) return;
        enableTXWidgets(false);
        transmitting = true;
        new ESLBlaster(
            usbSerialPort,
            Character.getNumericValue(blasterHWVersion),
            binding.main.progressbar,
            binding.main.textStatus2,
                result -> {
                    if (loopTX) {
                        IRTransmit(frames);
                    } else {
                        transmitting = false;
                        enableTXWidgets(true);
                    }
                }).execute(frames);
    }

    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_LONG).show();
                setScanPreview();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show();
                // TODO: Close app ?
            }
        }
    }

    Camera.PreviewCallback previewCB = new Camera.PreviewCallback() {
        public void onPreviewFrame(byte[] data, Camera camera) {
            String strSupplement;
            Camera.Size size = camera.getParameters().getPreviewSize();
            Image previewimage = new Image(size.width, size.height, "Y800"); // FourCC for monochrome image
            previewimage.setData(data);
            if (MainActivity.this.scanner.scanImage(previewimage) != 0) {
                Iterator<Symbol> it = MainActivity.this.scanner.getResults().iterator();
                //while (it.hasNext()) {
                String barcodeString = (it.next()).getData();
                //if (!barcodeString.equals(lastBarcodeString)) {
                    if (barcodeValid(barcodeString)) {
                        lastPLID = ((long) Integer.parseInt(barcodeString.substring(2, 7)) <<16) + Integer.parseInt(barcodeString.substring(7, 12));
                        String PLSerial = Long.toHexString(lastPLID);
                        while (PLSerial.length() < 8)
                            PLSerial = "0" + PLSerial;
                        if (PLSerial.length() > 8)
                            PLSerial = PLSerial.substring(8);
                        //lastPLType = Integer.parseInt(barcodeString.substring(12, 16));
                        strSupplement = "OK " + PLSerial.toUpperCase();
                        if (autoTX && !transmitting)
                            TransmitImage();
                    } else {
                        strSupplement = "INVALID";
                        lastPLID = 0;
                    }
                    binding.main.textLastScanned.setText("Last scanned:\n" + barcodeString + "\n (" + strSupplement + ")");
                //}
                //lastBarcodeString = barcodeString;
            }
        }
    };
    private final Runnable doAutoFocus = () -> {
        /*if (MainActivity.this.mCamera != null) {
            MainActivity.this.mCamera.autoFocus(MainActivity.this.autoFocusCB);
        }*/
    };

    Camera.AutoFocusCallback autoFocusCB = new Camera.AutoFocusCallback() {
        public void onAutoFocus(boolean success, Camera camera) {
            MainActivity.this.autoFocusHandler.postDelayed(MainActivity.this.doAutoFocus, 1000);
        }
    };

    public void setScanPreview() {
        // Init Camera preview
        this.autoFocusHandler = new Handler();
        CameraPreview mPreview = new CameraPreview(this, this.previewCB, this.autoFocusCB);
        this.preview = binding.main.cameraPreview;
        this.preview.addView(mPreview);

        // Init ZBar
        this.scanner = new ImageScanner();
        this.scanner.setConfig(0, Config.X_DENSITY, 3);
        this.scanner.setConfig(0, Config.Y_DENSITY, 3);
        this.scanner.setConfig(0, Config.MIN_LEN, 17);
        this.scanner.setConfig(0, Config.MAX_LEN, 17);
        // Disable all symbols except Code128
        this.scanner.setConfig(0, Config.ENABLE, 0);
        this.scanner.setConfig(Symbol.CODE128, Config.ENABLE, 1);
    }
    boolean barcodeValid(String barcodeString) {
        if (barcodeString.charAt(1) != '4') return false;
        if (Integer.parseInt(barcodeString.substring(5, 6)) > 53) return false;
        int sum = 0;
        for (int i = 0; i < barcodeString.length() - 1; i++)
            sum += barcodeString.charAt(i);
        return sum % 10 == Integer.parseInt(barcodeString.substring(16, 17));
    }

    public void status(String msg) {
        Snackbar.make(mainlayout, msg, Snackbar.LENGTH_LONG).setAction("Action", null).show();
    }

    @SuppressLint("DefaultLocale")
    public void testConnect() {
        UsbDevice device;
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        /*for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;*/
        Collection<UsbDevice> devices = usbManager.getDeviceList().values();
        if (devices.size() == 0)
            return;
        device = devices.iterator().next();
        if (device == null) {
            status("ESL Blaster connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            status("ESL Blaster connection failed: no driver found");
            return;
        }
        if (driver.getPorts().size() < 1) {
            status("ESL Blaster connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("ESL Blaster connection failed: permission denied");
            else
                status("ESL Blaster connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(57600, 8, 1, UsbSerialPort.PARITY_NONE);

            try {
                byte[] buffer = new byte[256];
                byte[] data = {(byte)'?'};
                usbSerialPort.write(data, 1000);
                if (usbSerialPort.read(buffer, 2000) > 0) {  // 2s timeout should be enough
                    String s = new String(buffer, StandardCharsets.UTF_8).substring(0, 12);
                    //Log.d("PHX", String.valueOf((char)buffer[0]));
                    if (s.startsWith("ESLBlaster")) {
                        status("ESL Blaster connected !");
                        blasterHWVersion = s.charAt(10);
                        blasterFWVersion = Character.digit(s.charAt(11), 10);
                        blasterConnected = true;
                        enableTXWidgets(true);
                        binding.main.textStatus.setText(String.format("ESLBlaster connected (HW %c, FW %d)", blasterHWVersion, blasterFWVersion));
                    }
                }
            } catch(IOException e) {
                e.printStackTrace();
                setDisconnected();
            }

        } catch (Exception e) {
            status("ESL Blaster connection failed: " + e.getMessage());
            setDisconnected();
        }
    }

    public void enableTXWidgets(boolean enable) {
        binding.main.buttonTXPage.setEnabled(enable);
        binding.main.buttonTXPageDM.setEnabled(enable);
        binding.main.buttonTXImage.setEnabled(enable);
    }

    private void setDisconnected() {
        blasterConnected = false;
        enableTXWidgets(false);
        status("ESL Blaster disconnected !");
        binding.main.textStatus.setText("ESL Blaster not connected");
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
            usbIoManager = null;
        }
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_about) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("App and ESL Blaster by furrtek\nhttps://github.com/furrtek/PrecIR/\n\nThanks to: Aoi, david4599, Deadbird, Dr.Windaube, Sigmounte, BiduleOhm, Virtualabs, LightSnivy")
                    .setTitle("About");
            AlertDialog dialog = builder.create();
            dialog.show();
            return true;
        } else if (id == R.id.action_help) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("Use an ESL Blaster with a USB OTG cable to use this app. Phone IR transmitters aren't fast enough to communicate with ESLs.\n\nPage change doesn't require a valid barcode (found printed on front or back of ESL) to be scanned. Changing segments or images do.\n\nDM ESL images won't update if the image is too big or in the wrong mode. A B/W 50x50px image should always work.")
                    .setTitle("Help");
            AlertDialog dialog = builder.create();
            dialog.show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}