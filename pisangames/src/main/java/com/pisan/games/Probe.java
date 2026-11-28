package com.pisan.games;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Oyuncuyu gondermeden once hedefe gercek bir istemci gibi (transfer niyetiyle) baglanip girisin
 * gercekten kabul edildigini kanitlar. Proxy'lerde (Velocity) arka sunucunun da cevap vermesi beklenir:
 * giris basarili olduktan sonra en az iki paket (arka sunucunun yapilandirma paketleri) gelmeli
 * ya da baglanti birkac saniye acik kalmali. Arada atilirsak (bakim, sunucu yok...) sunucu hazir degildir.
 * Yapilandirma asamasinda kalindigi icin test oyuncusu oyuna hic girmez.
 */
public final class Probe {
    public record Result(boolean ok, String detail) {}

    private Probe() {}

    public static Result check(String host, int port, int protocol, String name, Function<String, byte[]> cookies) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(8000);
            Conn c = new Conn(s);
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            varint(hs, protocol); str(hs, host); hs.write(port >> 8); hs.write(port); varint(hs, 3); // 3 = transfer
            c.send(0x00, hs.toByteArray());
            ByteArrayOutputStream ls = new ByteArrayOutputStream();
            str(ls, name);
            UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            DataOutputStream d = new DataOutputStream(ls); d.writeLong(u.getMostSignificantBits()); d.writeLong(u.getLeastSignificantBits());
            c.send(0x00, ls.toByteArray());

            // ---- login asamasi
            while (true) {
                Pkt p = c.read();
                switch (p.id) {
                    case 0x00 -> { return new Result(false, "atildi: " + p.text()); }
                    case 0x01 -> { return new Result(true, "online-mode (sifreleme istendi)"); }
                    case 0x03 -> c.threshold = p.varint();
                    case 0x04 -> { // login plugin istegi: anlamadik de
                        int msg = p.varint();
                        ByteArrayOutputStream r = new ByteArrayOutputStream(); varint(r, msg); r.write(0);
                        c.send(0x02, r.toByteArray());
                    }
                    case 0x05 -> { // cerez istegi
                        String key = p.string();
                        byte[] v = cookies.apply(key);
                        ByteArrayOutputStream r = new ByteArrayOutputStream(); str(r, key);
                        if (v == null) r.write(0); else { r.write(1); varint(r, v.length); r.write(v); }
                        c.send(0x04, r.toByteArray());
                    }
                    case 0x02 -> {
                        c.send(0x03, new byte[0]); // login acknowledged -> configuration
                        return afterLogin(c, s);
                    }
                    default -> { /* bilinmeyen: yoksay */ }
                }
            }
        } catch (EOFException e) {
            return new Result(false, "baglanti kapandi");
        } catch (Exception e) {
            return new Result(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Result afterLogin(Conn c, Socket s) throws IOException {
        long end = System.currentTimeMillis() + 6000;
        int packets = 0;
        while (System.currentTimeMillis() < end) {
            try {
                s.setSoTimeout((int) Math.max(50, end - System.currentTimeMillis()));
                c.read();
                packets++;
                if (packets >= 2) {
                    // atilma paketinden sonra baglanti hemen kapanir; kisa bir sure daha acik kalmali
                    s.setSoTimeout(700);
                    try { c.read(); } catch (SocketTimeoutException ignored) {}
                    return new Result(true, "girdi");
                }
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        return new Result(true, "girdi (baglanti acik)");
    }

    // ------------------------------------------------------------------ paketler
    private record Pkt(int id, DataInputStream in) {
        int varint() throws IOException { return Probe.readVarint(in); }
        String string() throws IOException { byte[] b = new byte[varint()]; in.readFully(b); return new String(b, StandardCharsets.UTF_8); }
        String text() { try { return string(); } catch (Exception e) { return "?"; } }
    }

    private static final class Conn {
        final DataInputStream in; final OutputStream out; int threshold = -1;
        Conn(Socket s) throws IOException { in = new DataInputStream(new BufferedInputStream(s.getInputStream())); out = new BufferedOutputStream(s.getOutputStream()); }

        void send(int id, byte[] body) throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(); varint(raw, id); raw.write(body);
            byte[] data = raw.toByteArray();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            if (threshold >= 0) {
                if (data.length >= threshold) {
                    Deflater def = new Deflater(); def.setInput(data); def.finish();
                    ByteArrayOutputStream z = new ByteArrayOutputStream(); byte[] buf = new byte[4096];
                    while (!def.finished()) z.write(buf, 0, def.deflate(buf));
                    def.end();
                    varint(frame, data.length); frame.write(z.toByteArray());
                } else { varint(frame, 0); frame.write(data); }
            } else frame.write(data);
            byte[] f = frame.toByteArray();
            ByteArrayOutputStream all = new ByteArrayOutputStream(); varint(all, f.length); all.write(f);
            out.write(all.toByteArray()); out.flush();
        }

        Pkt read() throws IOException {
            int len = readVarint(in);
            byte[] f = new byte[len]; in.readFully(f);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(f));
            if (threshold >= 0) {
                int dataLen = readVarint(d);
                if (dataLen != 0) {
                    Inflater inf = new Inflater(); inf.setInput(f, len - d.available(), d.available());
                    byte[] out = new byte[dataLen];
                    try { inf.inflate(out); } catch (DataFormatException e) { throw new IOException(e); } finally { inf.end(); }
                    d = new DataInputStream(new ByteArrayInputStream(out));
                }
            }
            return new Pkt(readVarint(d), d);
        }
    }

    static int readVarint(DataInputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do { b = in.readUnsignedByte(); v |= (b & 0x7F) << shift; shift += 7; if (shift > 35) throw new IOException("varint"); } while ((b & 0x80) != 0);
        return v;
    }

    static void varint(OutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    static void str(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8); varint(o, b.length); o.write(b);
    }
}
