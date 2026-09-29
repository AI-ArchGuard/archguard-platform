package io.github.aiarchguard.platform.agentdocument.internal.domain;

import io.github.aiarchguard.platform.agentdocument.DocumentTooLargeException;
import io.github.aiarchguard.platform.agentdocument.InvalidDocumentException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

public record DocumentContent(String text, String sha256, int byteSize, List<Fragment> fragments) {
    public static final int MAX_BYTES = 262144;
    private static final int MAX_PARAGRAPHS = 128;
    private static final int FRAGMENT_CHARS = 1024;
    private static final int MAX_FRAGMENTS = 256;
    private static final Pattern OBVIOUS_CREDENTIAL = Pattern.compile(
        "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|https://[^\\s/@]+:[^\\s/@]+@(?:github\\.com|gitlab\\.com))");

    public DocumentContent { fragments = List.copyOf(fragments); }

    public static DocumentContent parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new InvalidDocumentException("Document must not be empty");
        if (bytes.length > MAX_BYTES) throw new DocumentTooLargeException();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidDocumentException("Document must be strict UTF-8");
        }
        if (text.isBlank()) throw new InvalidDocumentException("Document must contain text");
        if (OBVIOUS_CREDENTIAL.matcher(text).find()) {
            throw new InvalidDocumentException("Document contains credential-shaped text");
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t') {
                throw new InvalidDocumentException("Document contains disallowed control characters");
            }
        }
        if (paragraphCount(text) > MAX_PARAGRAPHS) {
            throw new InvalidDocumentException("Document has too many paragraphs");
        }
        List<Fragment> fragments = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + FRAGMENT_CHARS);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String fragment = text.substring(start, end);
            fragments.add(new Fragment(fragments.size(), start, fragment, sha256(fragment.getBytes(StandardCharsets.UTF_8))));
            if (fragments.size() > MAX_FRAGMENTS) throw new InvalidDocumentException("Document has too many fragments");
            start = end;
        }
        return new DocumentContent(text, sha256(bytes), bytes.length, fragments);
    }

    private static int paragraphCount(String text) {
        int count = 0;
        boolean inParagraph = false;
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) inParagraph = false;
            else if (!inParagraph) { count++; inParagraph = true; }
        }
        return count;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Fragment(int index, int startOffset, String content, String sha256) {}
}
