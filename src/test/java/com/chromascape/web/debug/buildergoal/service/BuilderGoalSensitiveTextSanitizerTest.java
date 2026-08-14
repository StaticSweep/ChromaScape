package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuilderGoalSensitiveTextSanitizerTest {

  static final class SecretLeaf {
    @Override
    public String toString() {
      return "SUPERSECRET_CONTENT";
    }
  }

  @Test
  void sanitizeRedactsInlineProfileEmail() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize("--profile-email=user@example.com:supersecret");
    assertThat(result).contains("--profile-email=********");
    assertThat(result).doesNotContain("supersecret");
    assertThat(result).doesNotContain("user@example.com");
  }

  @Test
  void sanitizeRedactsSplitProfileEmail() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize("--profile-email user@example.com:supersecret");
    assertThat(result).contains("--profile-email ********");
    assertThat(result).doesNotContain("supersecret");
    assertThat(result).doesNotContain("user@example.com");
  }

  @Test
  void sanitizeIsCaseInsensitiveForOptionNames() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize("--PROFILE-EMAIL=user@example.com:supersecret");
    assertThat(result).contains("--profile-email=********");
    assertThat(result).doesNotContain("supersecret");
  }

  @Test
  void sanitizeRedactsQuotedProfileEmailValues() {
    String inline =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "--profile-email=\"user@example.com:supersecret\"");
    assertThat(inline).contains("--profile-email=********");
    assertThat(inline).doesNotContain("supersecret");

    String split =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "--profile-email 'user@example.com:supersecret'");
    assertThat(split).contains("--profile-email ********");
    assertThat(split).doesNotContain("supersecret");
  }

  @Test
  void sanitizeRedactsAuthorizationAndAuthBearers() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize(
            "Authorization: Bearer secret-token and auth: Bearer other-token");
    assertThat(result).contains("Authorization: Bearer ********");
    assertThat(result).doesNotContain("secret-token");
    assertThat(result).doesNotContain("other-token");
  }

  @Test
  void sanitizeRedactsPasswordPwdAndPassAssignments() {
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize("password=abc"))
        .contains("********")
        .doesNotContain("abc");
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize("password: abc"))
        .contains("********")
        .doesNotContain("abc");
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize("pwd=abc"))
        .contains("********")
        .doesNotContain("abc");
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize("pass=abc"))
        .contains("********")
        .doesNotContain("abc");
  }

  @Test
  void sanitizeRedactsEmailColonPasswordPayloads() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize("login using user@example.com:secret");
    assertThat(result).contains("email:********");
    assertThat(result).doesNotContain("secret");
  }

  @Test
  void sanitizeLeavesOrdinaryDisplayNamesIntact() {
    String result =
        BuilderGoalSensitiveTextSanitizer.sanitize("bigGreenMeat is online in world 360");
    assertThat(result).contains("bigGreenMeat");
    assertThat(result).doesNotContain("********");
  }

  @Test
  void sanitizeHandlesNullAndEmpty() {
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize(null)).isNull();
    assertThat(BuilderGoalSensitiveTextSanitizer.sanitize("")).isEmpty();
  }

  @Test
  void sanitizeRecursivelyProcessesMapsListsAndNestedStrings() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("launch_args_preview", "--profile-email=user@example.com:supersecret");
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("message", "Authorization: Bearer token-1");
    root.put("items", List.of(nested, "password=secret-2"));
    root.put("count", 42);

    Object sanitized = BuilderGoalSensitiveTextSanitizer.sanitizeRecursively(root);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) sanitized;
    assertThat(String.valueOf(result.get("message"))).doesNotContain("token-1");
    assertThat(String.valueOf(result.get("message"))).contains("********");
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) result.get("items");
    assertThat(String.valueOf(items.get(0))).doesNotContain("supersecret");
    assertThat(String.valueOf(items.get(1))).doesNotContain("secret-2");
    assertThat(result.get("count")).isEqualTo(42);
  }

  @Test
  void sanitizeRedactsQuotedPasswordAssignmentsContainingSpaces() {
    String doubleQuoted = BuilderGoalSensitiveTextSanitizer.sanitize("password=\"secret words\"");
    assertThat(doubleQuoted).contains("password=********");
    assertThat(doubleQuoted).doesNotContain("secret");
    assertThat(doubleQuoted).doesNotContain("words");

    String singleQuoted = BuilderGoalSensitiveTextSanitizer.sanitize("password: 'secret words'");
    assertThat(singleQuoted).contains("password: ********");
    assertThat(singleQuoted).doesNotContain("secret");

    String pwdColon = BuilderGoalSensitiveTextSanitizer.sanitize("pwd: \"a b c\"");
    assertThat(pwdColon).contains("pwd: ********");
    assertThat(pwdColon).doesNotContain("a b c");

    String passEquals = BuilderGoalSensitiveTextSanitizer.sanitize("pass=\"double  spaced\"");
    assertThat(passEquals).contains("pass=********");
    assertThat(passEquals).doesNotContain("double  spaced");

    String credentialColon =
        BuilderGoalSensitiveTextSanitizer.sanitize("credential: 'token value'");
    assertThat(credentialColon).contains("credential: ********");
    assertThat(credentialColon).doesNotContain("token value");

    String bearerQuoted =
        BuilderGoalSensitiveTextSanitizer.sanitize("Authorization: Bearer \"my token\"");
    assertThat(bearerQuoted).contains("Authorization: Bearer ********");
    assertThat(bearerQuoted).doesNotContain("my token");
  }

  @Test
  void sanitizeAndFreezeReturnsDeeplyImmutableNestedStructures() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("inner", "password=secret-3");
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nested", nested);
    root.put(
        "items", List.of("pwd: token-4", new LinkedHashMap<>(Map.of("deep", "pass=secret-5"))));
    root.put("count", 42);
    root.put("nullable", null);

    Object frozen = BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(root);

    @SuppressWarnings("unchecked")
    Map<String, Object> frozenMap = (Map<String, Object>) frozen;
    @SuppressWarnings("unchecked")
    Map<String, Object> frozenNested = (Map<String, Object>) frozenMap.get("nested");
    @SuppressWarnings("unchecked")
    List<Object> frozenItems = (List<Object>) frozenMap.get("items");

    assertThatThrownBy(() -> frozenMap.put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> frozenNested.put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> frozenItems.add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> frozenDeep = (Map<String, Object>) frozenItems.get(1);
    assertThatThrownBy(() -> frozenDeep.put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThat(String.valueOf(frozenNested.get("inner"))).doesNotContain("secret-3");
    assertThat(String.valueOf(frozenItems.get(0))).doesNotContain("token-4");
    assertThat(frozenMap.get("count")).isEqualTo(42);
    assertThat(frozenMap.get("nullable")).isNull();
  }

  @Test
  void sanitizeAndFreezeCopiesSetsCollectionsAndArraysIntoImmutableLists() {
    Set<String> setValue = new LinkedHashSet<>();
    setValue.add("password=secret-set");
    List<Object> listValue = new ArrayList<>();
    listValue.add("pwd: secret-list");
    listValue.add(new String[] {"pass=secret-obj-array"});
    listValue.add(new int[] {1, 2, 3});
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nested_set", setValue);
    root.put("nested_collection", new ArrayDeque<>(List.of("credential: secret-queue")));
    root.put("nested_list", listValue);
    root.put("nullable", null);
    root.put("count", 42);

    Object frozen = BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(root);

    @SuppressWarnings("unchecked")
    Map<String, Object> frozenMap = (Map<String, Object>) frozen;
    @SuppressWarnings("unchecked")
    List<Object> frozenSet = (List<Object>) frozenMap.get("nested_set");
    assertThatThrownBy(() -> frozenSet.add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(String.valueOf(frozenSet.get(0))).doesNotContain("secret-set");
    @SuppressWarnings("unchecked")
    List<Object> frozenCollection = (List<Object>) frozenMap.get("nested_collection");
    assertThat(String.valueOf(frozenCollection.get(0))).doesNotContain("secret-queue");
    @SuppressWarnings("unchecked")
    List<Object> frozenList = (List<Object>) frozenMap.get("nested_list");
    assertThatThrownBy(() -> frozenList.add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(String.valueOf(frozenList.get(0))).doesNotContain("secret-list");
    @SuppressWarnings("unchecked")
    List<Object> objArray = (List<Object>) frozenList.get(1);
    assertThat(String.valueOf(objArray.get(0))).doesNotContain("secret-obj-array");
    @SuppressWarnings("unchecked")
    List<Object> primArray = (List<Object>) frozenList.get(2);
    assertThat(primArray).containsExactly(1, 2, 3);
    assertThat(frozenMap.get("nullable")).isNull();
    assertThat(frozenMap.get("count")).isEqualTo(42);
  }

  @Test
  void sanitizeAndFreezeCopiesMeanMutatingOriginalsCannotAffectSnapshot() {
    Map<String, Object> nestedMap = new LinkedHashMap<>();
    nestedMap.put("inner", "password=original-map");
    List<Object> listValue = new ArrayList<>();
    listValue.add("pwd: original-list");
    Set<String> setValue = new LinkedHashSet<>();
    setValue.add("credential: original-set");
    String[] arrayValue = {"pass=original-array"};
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nested_map", nestedMap);
    root.put("nested_list", listValue);
    root.put("nested_set", setValue);
    root.put("nested_array", arrayValue);

    Object frozen = BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(root);

    nestedMap.put("inner", "password=mutated-map");
    listValue.add("pwd: mutated-list");
    setValue.add("credential: mutated-set");
    arrayValue[0] = "pass=mutated-array";
    root.put("mutated", true);

    @SuppressWarnings("unchecked")
    Map<String, Object> frozenMap = (Map<String, Object>) frozen;
    assertThat(frozenMap.containsKey("mutated")).isFalse();
    @SuppressWarnings("unchecked")
    Map<String, Object> frozenNestedMap = (Map<String, Object>) frozenMap.get("nested_map");
    assertThat(String.valueOf(frozenNestedMap.get("inner"))).doesNotContain("mutated-map");
    @SuppressWarnings("unchecked")
    List<Object> frozenList = (List<Object>) frozenMap.get("nested_list");
    assertThat(frozenList).hasSize(1);
    assertThat(String.valueOf(frozenList.get(0))).doesNotContain("mutated-list");
    @SuppressWarnings("unchecked")
    List<Object> frozenSet = (List<Object>) frozenMap.get("nested_set");
    assertThat(frozenSet).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Object> frozenArray = (List<Object>) frozenMap.get("nested_array");
    assertThat(String.valueOf(frozenArray.get(0))).doesNotContain("mutated-array");
  }

  @Test
  void unsupportedMutableLeafTypesAreRejectedWithoutExposingToStringContent() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("secret_leaf", new SecretLeaf());

    assertThatThrownBy(() -> BuilderGoalSensitiveTextSanitizer.sanitizeAndFreeze(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SUPERSECRET_CONTENT")
        .hasMessageNotContaining("secret_leaf");
  }
}
