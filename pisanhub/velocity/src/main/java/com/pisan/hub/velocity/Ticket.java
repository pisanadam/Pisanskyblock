package com.pisan.hub.velocity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Lobide giris yapmis oyuncu icin imzali "bilet". Transferden once istemciye cerez (pisan:ticket)
 * olarak yazilir; hedef sunucu cerezi okuyup ayni gizli anahtarla dogrular, boylece oyuncuya
 * tekrar sifre sorulmaz ve lobiyi atlayip dogrudan baglanan sahte isimler iceri giremez.
 * Bicim: "isim|bitis(ms)|nonce|imza" (imza = base64url(HMAC-SHA256(gizli, "isim|bitis|nonce"))).
 */
public final class Ticket {
    public static final String NAMESPACE = "pisan", KEY = "ticket";
    private static final SecureRandom RNG = new SecureRandom();

    private Ticket() {}

    public static byte[] create(String secret, String name, long ttlMillis) {
        byte[] n = new byte[9];
        RNG.nextBytes(n);
        String body = name + "|" + (System.currentTimeMillis() + ttlMillis) + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        return (body + "|" + sign(secret, body)).getBytes(StandardCharsets.UTF_8);
    }

    /** Gecerliyse bilet sahibinin ismini, degilse null dondurur. */
    public static String verify(String secret, byte[] data) {
        if (data == null || secret == null || secret.isBlank()) return null;
        String s = new String(data, StandardCharsets.UTF_8);
        int cut = s.lastIndexOf('|');
        if (cut < 0) return null;
        String body = s.substring(0, cut);
        if (!MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8), s.substring(cut + 1).getBytes(StandardCharsets.UTF_8))) return null;
        String[] p = body.split("\\|");
        if (p.length != 3) return null;
        try { if (Long.parseLong(p[1]) < System.currentTimeMillis()) return null; } catch (NumberFormatException e) { return null; }
        return p[0];
    }

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
