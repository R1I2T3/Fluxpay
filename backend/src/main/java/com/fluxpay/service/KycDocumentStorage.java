package com.fluxpay.service;

import com.fluxpay.exception.KycException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Private local storage; never mounted as a public/static directory. */
@Component
public class KycDocumentStorage {
  private final Path root;

  public KycDocumentStorage(
      @Value("${fluxpay.kyc.storage-directory:temp_images}") String directory) {
    root = Path.of(directory).toAbsolutePath().normalize();
  }

  public record Upload(String name, String type, byte[] bytes) {}

  public Upload validate(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
      throw invalid("Each document must be non-empty and no larger than 5 MB.");
    String name = file.getOriginalFilename();
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl))
      throw invalid("Choose a document with a valid filename.");
    try {
      byte[] bytes = file.getBytes();
      String type = file.getContentType();
      boolean pdf =
          "application/pdf".equals(type)
              && name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
              && bytes.length > 10
              && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")
              && new String(
                      bytes,
                      Math.max(0, bytes.length - 1024),
                      Math.min(1024, bytes.length),
                      java.nio.charset.StandardCharsets.ISO_8859_1)
                  .contains("%%EOF");
      boolean image = false;
      if ("image/png".equals(type) || "image/jpeg".equals(type)) {
        try (var input =
            javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
          var readers = javax.imageio.ImageIO.getImageReaders(input);
          if (readers.hasNext()) {
            var reader = readers.next();
            try {
              reader.setInput(input);
              String format = reader.getFormatName();
              image =
                  ("image/png".equals(type)
                          ? format.equalsIgnoreCase("png")
                          : format.equalsIgnoreCase("jpeg"))
                      && (long) reader.getWidth(0) * reader.getHeight(0) <= 25_000_000
                      && reader.read(0) != null;
            } finally {
              reader.dispose();
            }
          }
        }
      }
      if (!pdf && !image)
        throw invalid("Upload a valid PDF, JPG or PNG document (images up to 25 megapixels).");
      return new Upload(name.trim(), type, bytes);
    } catch (IOException e) {
      throw invalid("The document could not be read. Choose another file.");
    }
  }

  public String save(UUID id, byte[] bytes) {
    boolean created = false;
    try {
      Files.createDirectories(root);
      if (Files.isSymbolicLink(root)) throw new IOException("Symbolic storage directory");
      try (var output = Files.newOutputStream(path("local:" + id), StandardOpenOption.CREATE_NEW)) {
        created = true;
        output.write(bytes);
      }
      return "local:" + id;
    } catch (IOException | IllegalArgumentException e) {
      if (created) remove("local:" + id);
      throw new KycException(
          KycException.KYC_STORAGE_UNAVAILABLE,
          "Document storage is unavailable. Please try again later.");
    }
  }

  public byte[] read(String key) {
    try {
      Path file = path(key);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > 5 * 1024 * 1024) throw new IOException("Unavailable document");
      return Files.readAllBytes(file);
    } catch (IOException | IllegalArgumentException e) {
      throw new KycException(
          KycException.KYC_NOT_FOUND,
          "The original document is unavailable. Please contact support.");
    }
  }

  public void remove(String key) {
    if (key == null || !key.startsWith("local:")) return;
    try {
      Files.deleteIfExists(path(key));
    } catch (IOException | IllegalArgumentException e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("KYC document cleanup requires attention");
    }
  }

  private Path path(String key) {
    if (!key.startsWith("local:")) throw new IllegalArgumentException("Not a local document");
    UUID id = UUID.fromString(key.substring(6));
    Path file = root.resolve(id + ".bin").normalize();
    if (!file.getParent().equals(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(file))
      throw new IllegalArgumentException("Invalid document location");
    return file;
  }

  private KycException invalid(String message) {
    return new KycException(KycException.VALIDATION, message);
  }
}
