package org.furrtek.pricehax;

import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.content.Context;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.snackbar.Snackbar;
import org.furrtek.pricehax.databinding.FragmentFirstBinding;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class FirstFragment extends Fragment {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";

    private FragmentFirstBinding binding;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private SerialInputOutputManager usbIoManager;
    private boolean connected = false;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public byte[] Transmit(List<Byte> list) {
        byte[] data = new byte[list.size() + 5 + 1];
        data[0] = 76;
        data[1] = (byte)list.size();
        data[2] = 30;
        data[3] = 100;
        data[4] = 0;
        for(int i = 0; i < list.size(); i++) {
            data[i + 5] = list.get(i).byteValue();
        }
        data[data.length - 1] = 84;

        // Debug
        String hex_str = "";
        for (byte b : data) {
            hex_str += String.format("%02X ", b);
        }

        Log.d("TX", hex_str);

        return data;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonFirst.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                testconnect(view);
            }
        });

        binding.buttonTxA.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //byte data[] = {76, 13, 30, 100, 0, (byte)0x85, 0x00, 0x00, 0x00, 0x00, 0x06, (byte)0xF1, 0x00, 0x00, 0x00, 0x0A, 0x5D, 0x14, 84};

                IRFrame frame = new IRFrame();
                frame.PLID = 0;
                frame.protocol = (byte)0x85;
                Byte[] pl = {0x06, (byte)0x03, 0x00, 0x00};

                EditText text = (EditText)getView().findViewById(R.id.customValue);
                byte b = (byte)Integer.parseInt(text.getText().toString(), 16);
                pl[1] = b;

                frame.payload = Arrays.asList(pl);

                byte[] data = FirstFragment.this.Transmit(frame.getRawData(false));

                try {
                    if (connected)
                        usbSerialPort.write(data, 1000);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
            }
        });

        binding.buttonTxB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                byte data[] = {76, 13, 30, 100, 0, (byte)0x85, 0x00, 0x00, 0x00, 0x00, 0x06, 0x09, 0x00, 0x00, 0x00, 0x01, 0x08, 0x6F, 84};
                try {
                    if (connected)
                        usbSerialPort.write(data, 1000);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void status(View view, String msg) {
        Snackbar.make(view, msg, Snackbar.LENGTH_LONG).setAction("Action", null).show();
    }

    public void testconnect(View view) {
                /*NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment);*/
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        /*for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;*/
        Collection<UsbDevice> devices = usbManager.getDeviceList().values();
        if (devices.size() == 0)
            return;
        device = devices.iterator().next();
        if(device == null) {
            status(view,"connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            //status(view,"connection failed: no driver for device");
            //status(view, String.valueOf(device.getVendorId()));   // 1155 = 0x483 ok
            status(view, String.valueOf(device.getProductId()));
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status(view,"connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status(view,"connection failed: permission denied");
            else
                status(view,"connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(57600, 8, 1, UsbSerialPort.PARITY_NONE);
            //if(withIoManager) {
            //    usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            //    usbIoManager.start();
            //}
            status(view,"connected");

            connected = true;
        } catch (Exception e) {
            status(view,"connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

}