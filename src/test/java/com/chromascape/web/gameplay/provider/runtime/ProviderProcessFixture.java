package com.chromascape.web.gameplay.provider.runtime;

import java.nio.charset.StandardCharsets;

/** Child-process fixture for transport boundary tests. */
public final class ProviderProcessFixture {
  private ProviderProcessFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && "sleep".equals(args[0])) {
      Thread.sleep(10_000);
      return;
    }
    byte[] input = System.in.readAllBytes();
    System.out.print(new String(input, StandardCharsets.UTF_8));
  }
}
