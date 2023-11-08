package net.jimblackler.jsonschemafriend;

import com.fasterxml.jackson.core.JsonPointer;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class PathUtils {
  public static final String ESCAPED_EMPTY = "~2";
  private static final Logger LOG = Logger.getLogger(PathUtils.class.getName());

  public static URI append(URI uri, String value) {
    String uriString = uri.toString();
    if (!uriString.contains("#")) {
      uriString += "#";
    }

    if (uriString.charAt(uriString.length() - 1) != '/') {
      uriString += "/";
    }

    value = value.replace("~", "~0").replace("/", "~1");
    value = uriComponentEncode(value);
    if (value.isEmpty()) {
      value = ESCAPED_EMPTY;
    }

    return URI.create(uriString + value);
  }

  static URI baseDocumentFromUri(URI path) {
    try {
      return new URI(path.getScheme(), path.getSchemeSpecificPart(), null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Object fetchFromPath(Object document, String path) throws MissingPathException {
    if (path == null || path.isEmpty()) {
      return document;
    }

    // Special escape string for an empty key in a path.
    // Empty keys mid-path could be represented with // (nothing between the separators), but
    // that would not work for keys at the end of the path.
    path = path.replace(ESCAPED_EMPTY, "");
    JsonPointer jsonPointer = JsonPointer.compile(path);

    return queryFrom(jsonPointer, document);
  }

  private static Object queryFrom(JsonPointer jsonPointer, Object object)
      throws MissingPathException {
    if (jsonPointer.matches()) {
      return object;
    }
    if (object instanceof List) {
      int matchingIndex = jsonPointer.getMatchingIndex();
      List<Object> list = (List<Object>) object;
      if (matchingIndex < 0 || matchingIndex >= list.size()) {
        throw new MissingPathException(jsonPointer.toString());
      }
      return queryFrom(jsonPointer.tail(), list.get(matchingIndex));
    }
    if (object instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) object;
      String property;
      try {
        property =
            URLDecoder.decode(jsonPointer.getMatchingProperty(), StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        throw new MissingPathException(e);
      }
      if (!map.containsKey(property)) {
        throw new MissingPathException(jsonPointer.toString());
      }
      return queryFrom(jsonPointer.tail(), map.get(property));
    }
    throw new MissingPathException(jsonPointer.toString());
  }

  public static Object modifyAtPath(Object document, String path, Object newObject)
      throws MissingPathException {
    if (path == null || path.isEmpty()) {
      return newObject;
    }
    try {
      String parentPath = getParentPath(path);
      path = path.replace(ESCAPED_EMPTY, "");
      JsonPointer jsonPointer = JsonPointer.compile(parentPath);

      Object parentObject = queryFrom(jsonPointer, document);
      String lastPart = getLastPart(path);
      lastPart = URLDecoder.decode(lastPart);
      lastPart = jsonPointerUnescape(lastPart);

      if (parentObject instanceof Map) {
        ((Map<String, Object>) parentObject).put(lastPart, newObject);
        return document;
      }
      if (parentObject instanceof List) {
        ((List<Object>) parentObject).add(Integer.parseInt(lastPart), newObject);
        return document;
      }
      throw new MissingPathException("Could not modify document");

    } catch (IllegalArgumentException ex) {
      throw new MissingPathException("Probable attempt to use an $id as a URL", ex);
    }
  }

  public static void deleteAtPath(Object document, String path) throws MissingPathException {
    if (path == null || path.isEmpty()) {
      throw new MissingPathException();
    }
    try {
      String parentPath = getParentPath(path);
      path = path.replace(ESCAPED_EMPTY, "");
      JsonPointer jsonPointer = JsonPointer.compile("#" + parentPath);

      Object parentObject = queryFrom(jsonPointer, document);
      String lastPart = getLastPart(path);
      lastPart = URLDecoder.decode(lastPart);
      lastPart = jsonPointerUnescape(lastPart);

      if (parentObject instanceof Map) {
        ((Map<String, Object>) parentObject).remove(lastPart);
        return;
      }
      if (parentObject instanceof List) {
        ((List<Object>) parentObject).remove(Integer.parseInt(lastPart));
        return;
      }
      throw new MissingPathException("Could not modify document");

    } catch (IllegalArgumentException ex) {
      throw new MissingPathException("Probable attempt to use an $id as a URL", ex);
    }
  }

  private static String getParentPath(String path) throws MissingPathException {
    int i = path.lastIndexOf('/');
    if (i == -1) {
      throw new MissingPathException("No parent");
    }
    return path.substring(0, i);
  }

  private static String getLastPart(String path) {
    int i = path.lastIndexOf('/');
    if (i == -1) {
      return path;
    }
    return path.substring(i + 1);
  }

  static String uriComponentEncode(String value) {
    String encoded;
    try {
      encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }

    // $ is a common character in schema paths, and it doesn't strictly require escaping, so for
    // aesthetic reasons we don't escape it.
    encoded = encoded.replace("%24", "$");

    // Encoding tilde causes a conflict with the JSON Pointer encoding.
    encoded = encoded.replace("%7E", "~");

    return encoded;
  }

  private static String jsonPointerUnescape(String token) {
    // This matches the JSONPointer escaping method which may not match RFC 6901 which does not
    // require quotes to be encoded.
    return token.replace("~1", "/").replace("~0", "~").replace("\\\"", "\"").replace("\\\\", "\\");
  }

  public static URI getParent(URI uri) {
    String pointer = uri.getRawFragment();
    if (pointer == null) {
      return null;
    }
    int i = pointer.lastIndexOf('/');
    if (i == -1) {
      return null;
    }
    try {
      return new URI(uri.getScheme(), uri.getHost(), uri.getPath(), pointer.substring(0, i));
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Generate a resolved URI from a base and child URI.
   *
   * <p>This is a replacement for java.net.URI.resolve that can handle 'non-authority' schemes like
   * urn: or jar:
   *
   * @param base The base URI.
   * @param child The child URI.
   * @return The resolved URI.
   */
  static URI resolve(URI base, URI child) {
    if (child.getScheme() != null) {
      return child;
    }

    String childString = child.toString();
    String baseString = base.toString();

    int i = baseString.indexOf('#');
    String baseWithoutFragment = i == -1 ? baseString : baseString.substring(0, i);

    if (childString.charAt(0) == '#') {
      return URI.create(baseWithoutFragment + childString);
    }

    // Find the 'parent' of the base URI.
    int i2 = baseWithoutFragment.lastIndexOf('/');
    if (i2 == -1) {
      // We can go up to 'non authority' schemes like urn: or jar:.
      i2 = baseWithoutFragment.lastIndexOf(':');
      if (i2 == -1) {
        return child;
      }
    }
    return URI.create(
        baseWithoutFragment.substring(0, i2 + 1)
            + (childString.charAt(0) == '/' ? childString.substring(1) : childString));
  }

  /**
   * Applications are supposed to escape $refs but they often don't. We help out applications by
   * attempting to escape some of these characters. Some we can never fix in this way, such as the
   * percent character or forward slash character.
   *
   * @param ref The URI to possibly fix.
   * @return The fixed ref.
   */
  public static String fixUnescaped(String ref) {
    int i = ref.indexOf('#');
    if (i == -1) {
      return ref;
    }

    String fragment = ref.substring(i + 1);
    String value = fragment;
    value = value.replace("\t", "%09");
    value = value.replace("\n", "%0A");
    value = value.replace("\f", "%0C");
    value = value.replace("\r", "%0D");
    value = value.replace("!", "%21");
    value = value.replace("\"", "%22");
    value = value.replace("#", "%23");
    value = value.replace("+", "%2B");
    value = value.replace(":", "%3A");
    value = value.replace("<", "%3C");
    value = value.replace(">", "%3E");
    value = value.replace("?", "%3F");
    value = value.replace("\\", "%5C");
    value = value.replace("^", "%5E");
    value = value.replace("`", "%60");
    value = value.replace("{", "%7B");
    value = value.replace("|", "%7C");
    value = value.replace("}", "%7D");
    if (value.equals(fragment)) {
      return ref;
    }
    String ref2 = ref.substring(0, i + 1) + value;
    LOG.warning(
        "Converting unescaped reference " + ref + " to JSON Schema legal $ref form " + ref2);
    return ref2;
  }

  /**
   * Convert a URI to its standard form, for the purposes of looking up URIs in internal
   * dictionaries. Where there are multiple valid ways to express the same URI we have to chose one
   * as the canonical form if URIs are to be used as keys in storage structures.
   *
   * <p>For example; http://example.com, http://example.com# and http://example.com#/ are all valid
   * but equivalent pointers.
   *
   * @param uri The URL to normalize.
   * @return The normalized form of the URI.
   */
  static URI normalize(URI uri) {
    String uriString = uri.toString();
    int length = uriString.length();
    if (uriString.endsWith("#")) {
      return URI.create(uriString.substring(0, length - "#".length()));
    }
    if (uriString.endsWith("#/")) {
      return URI.create(uriString.substring(0, length - "#/".length()));
    }
    return uri;
  }
}
