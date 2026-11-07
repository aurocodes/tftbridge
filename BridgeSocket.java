package com.example.tftbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ISOLATED TRANSPORT LAYER. Nothing else in the app knows about sockets or wire formats.
 *
 * Everything you learn from the Bajaj APK goes in exactly three places:
 *   1. The constants below            (transport, host/port or BT address/UUID)
 *   2. connect() -> handshake         (any auth / hello packet the console expects first)
 *   3. buildVideoPacket / buildNavPacket / buildNavEndedPacket   (framing + headers)
 *
 * Threading: one single-thread executor serialises all I/O, so callers never block.
 */
public final class BridgeSocket {

    private static final String TAG = "TFT_NET";

    public enum Transport { TCP, UDP, BLUETOOTH_RFCOMM }

    // ===== 1. CONNECTION PARAMETERS (replace with values from the Bajaj APK / pcap) =====
    private static final Transport TRANSPORT = Transport.TCP;
    private static final String HOST = "192.168.4.1";          // TODO console IP (if Wi-Fi AP/Direct)
    private static final int PORT = 8000;                       // TODO
    private static final String BT_MAC = "00:00:00:00:00:00";   // TODO bonded console address
    private static final UUID BT_UUID =                         // TODO default = standard SPP UUID
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // If the console is BLE (GATT) rather than classic RFCOMM, this class needs a BluetoothGatt
    // writer instead - the packet builders below stay the same.

    private static final int UDP_CHUNK = 1400;                  // keep under MTU
    private static final int MAX_PENDING_VIDEO = 4;             // drop video frames if I/O backs up

    // ===== singleton =====
    private static final BridgeSocket INSTANCE = new BridgeSocket();
    public static BridgeSocket get() { return INSTANCE; }
    private BridgeSocket() {}

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingVideo = new AtomicInteger();

    private Context appCtx;
    private Socket tcp;
    private DatagramSocket udp;
    private InetAddress udpAddr;
    private BluetoothSocket bt;
    private OutputStream out;
    private long lastConnectAttemptMs = 0;
    private int seq = 0;                                        // packet counter if the protocol needs one

    // ------------------------------------------------------------------ public API

    public void connect(Context ctx) {
        appCtx = ctx.getApplicationContext();
        io.execute(this::connectBlocking);
    }

    public void close() {
        io.execute(this::closeBlocking);
    }

    /** @param h264 Annex-B bytes straight from MediaCodec (config=true -> SPS/PPS only). */
    public void sendVideo(byte[] h264, long ptsUs, boolean keyFrame, boolean config) {
        if (pendingVideo.get() >= MAX_PENDING_VIDEO && !keyFrame && !config) return; // never drop SPS/PPS/IDR
        pendingVideo.incrementAndGet();
        io.execute(() -> {
            try { write(buildVideoPacket(h264, ptsUs, keyFrame, config)); }
            finally { pendingVideo.decrementAndGet(); }
        });
    }

    public void sendNav(String maneuver, String distance, String rawTitle, String rawText) {
        io.execute(() -> write(buildNavPacket(maneuver, distance, rawTitle, rawText)));
    }

    public void sendNavEnded() {
        io.execute(() -> write(buildNavEndedPacket()));
    }

    // ------------------------------------------------------------------ 3. PACKET BUILDERS

    /**
     * INJECT VIDEO FRAMING HERE.
     * Typical things to fill in from the APK:
     *   - magic/sync bytes, protocol version
     *   - message type id for "video frame"
     *   - payload length (check endianness! ByteBuffer defaults to BIG_ENDIAN)
     *   - timestamp format (ptsUs is microseconds; the console may want ms or a 90 kHz clock)
     *   - flags: keyframe / config bit
     *   - checksum/CRC over header or payload
     *   - whether the console wants Annex-B (start codes, what we give you) or length-prefixed AVCC
     *     (if AVCC, convert 00 00 00 01 -> 4-byte length here)
     */
    private byte[] buildVideoPacket(byte[] h264, long ptsUs, boolean keyFrame, boolean config) {
        // ---- PLACEHOLDER: 16-byte dummy header + payload. Replace entirely. ----
        ByteBuffer b = ByteBuffer.allocate(16 + h264.length);
        b.putInt(0xDEADBEEF);                 // TODO magic
        b.putShort((short) 0x0001);           // TODO msg type: video
        b.putShort((short) ((keyFrame ? 1 : 0) | (config ? 2 : 0))); // TODO flags
        b.putInt(h264.length);                // TODO length field semantics
        b.putInt((int) (ptsUs / 1000));       // TODO timestamp units
        b.put(h264);
        seq++;
        return b.array();
    }

    /**
     * INJECT TURN-BY-TURN TEXT FRAMING HERE.
     * Find in the APK how it serialises a Mappls maneuver: likely an enum/icon id (turn-left,
     * roundabout, ...) + distance (meters as int?) + street name string. You'll probably need a
     * mapping from Google's free-text ("Turn left onto X") to the console's maneuver icon id.
     * Write that mapping in mapManeuverToConsoleIcon() below.
     */
    private byte[] buildNavPacket(String maneuver, String distance, String rawTitle, String rawText) {
        // ---- PLACEHOLDER: plain UTF-8 JSON. Replace entirely. ----
        String json = "{\"type\":\"nav\",\"icon\":" + mapManeuverToConsoleIcon(maneuver)
                + ",\"maneuver\":\"" + esc(maneuver) + "\",\"dist\":\"" + esc(distance) + "\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.putInt(0xDEADBEEF);                 // TODO magic
        b.putShort((short) 0x0002);           // TODO msg type: nav text
        b.putShort((short) payload.length);   // TODO length
        b.put(payload);
        return b.array();
    }

    private byte[] buildNavEndedPacket() {
        // TODO: whatever the console expects when navigation stops (or nothing).
        return buildNavPacket("", "", "", "");
    }

    /** TODO: map free-text maneuver to the console's icon/enum value. */
    private int mapManeuverToConsoleIcon(String maneuver) {
        if (maneuver == null) return 0;
        String m = maneuver.toLowerCase();
        if (m.contains("u-turn")) return 8;       // placeholders - replace with real ids
        if (m.contains("roundabout")) return 7;
        if (m.contains("left")) return 2;
        if (m.contains("right")) return 3;
        if (m.contains("straight") || m.contains("continue")) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ 2. CONNECT + HANDSHAKE

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested in MainActivity
    private void connectBlocking() {
        closeBlocking();
        lastConnectAttemptMs = System.currentTimeMillis();
        try {
            switch (TRANSPORT) {
                case TCP:
                    tcp = new Socket();
                    tcp.setTcpNoDelay(true);
                    tcp.connect(new InetSocketAddress(HOST, PORT), 3000);
                    out = tcp.getOutputStream();
                    break;
                case UDP:
                    udp = new DatagramSocket();
                    udpAddr = InetAddress.getByName(HOST);
                    break;
                case BLUETOOTH_RFCOMM:
                    BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
                    BluetoothAdapter ad = bm.getAdapter();
                    BluetoothDevice dev = ad.getRemoteDevice(BT_MAC);
                    ad.cancelDiscovery();
                    bt = dev.createRfcommSocketToServiceRecord(BT_UUID);
                    bt.connect();
                    out = bt.getOutputStream();
                    break;
            }
            Log.i(TAG, "Connected via " + TRANSPORT);

            // ---- INJECT HANDSHAKE HERE (if any) ----
            // e.g. write(buildHelloPacket()); then read the console's ACK from tcp.getInputStream()
            // The official app may also send device info / app version / "map provider" packets first.

        } catch (Exception e) {
            Log.w(TAG, "Connect failed: " + e);
            closeBlocking();
        }
    }

    private void closeBlocking() {
        try { if (tcp != null) tcp.close(); } catch (IOException ignored) {}
        try { if (bt != null) bt.close(); } catch (IOException ignored) {}
        if (udp != null) udp.close();
        tcp = null; bt = null; udp = null; out = null;
    }

    // ------------------------------------------------------------------ raw write (runs on io thread)

    private void write(byte[] packet) {
        boolean connected = (TRANSPORT == Transport.UDP) ? udp != null : out != null;
        if (!connected) {
            // Retry at most every 2 s so we don't hammer the radio.
            if (System.currentTimeMillis() - lastConnectAttemptMs > 2000) connectBlocking();
            return;
        }
        try {
            if (TRANSPORT == Transport.UDP) {
                for (int off = 0; off < packet.length; off += UDP_CHUNK) {
                    int len = Math.min(UDP_CHUNK, packet.length - off);
                    udp.send(new DatagramPacket(packet, off, len, udpAddr, PORT));
                }
            } else {
                out.write(packet);
                out.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Write failed, will reconnect: " + e);
            closeBlocking();
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
