package com.corebanking.kernel;

import java.util.Locale;
import java.util.Set;

/**
 * File-type check by leading bytes ("magic numbers") for uploaded documents (US-032, ASVS V5): the declared
 * content type must match what the file really is, so an executable or a script cannot be stored as a "PDF".
 */
public final class FileSignatures {

    private FileSignatures() {}

    public static final String PDF = "application/pdf";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    /** Content types accepted for KYC documents. */
    public static final Set<String> KYC_TYPES = Set.of(PDF, JPEG, PNG);

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** The type the bytes really are, or null when they are none of the accepted types. */
    public static String detect(byte[] content) {
        if (content == null) return null;
        if (startsWith(content, PDF_MAGIC)) return PDF;
        if (startsWith(content, PNG_MAGIC)) return PNG;
        if (startsWith(content, JPEG_MAGIC)) return JPEG;
        return null;
    }

    /** True when {@code declared} (parameters such as charset ignored) is accepted and equals the detected type. */
    public static boolean matches(String declared, byte[] content) {
        String base = baseType(declared);
        return base != null && KYC_TYPES.contains(base) && base.equals(detect(content));
    }

    /** "Image/PNG; q=1" → "image/png". */
    public static String baseType(String contentType) {
        if (contentType == null) return null;
        int semi = contentType.indexOf(';');
        String base = (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        return base.isEmpty() ? null : base;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (content[i] != magic[i]) return false;
        return true;
    }
}
