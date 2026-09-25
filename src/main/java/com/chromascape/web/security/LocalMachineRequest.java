package com.chromascape.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/** Utilities for recognizing requests that originate from the application host itself. */
public final class LocalMachineRequest {

  private LocalMachineRequest() {}

  /**
   * Returns true for loopback and for an address assigned to one of this host's network interfaces.
   * This deliberately does not treat an entire private subnet as local.
   */
  public static boolean isLocalMachine(HttpServletRequest request) {
    if (request == null || request.getRemoteAddr() == null || request.getRemoteAddr().isBlank()) {
      return false;
    }
    try {
      InetAddress remote = InetAddress.getByName(request.getRemoteAddr());
      if (remote.isLoopbackAddress()) {
        return true;
      }
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        return false;
      }
      while (interfaces.hasMoreElements()) {
        Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
        while (addresses.hasMoreElements()) {
          if (remote.equals(addresses.nextElement())) {
            return true;
          }
        }
      }
    } catch (UnknownHostException | SocketException | IllegalArgumentException e) {
      return false;
    }
    return false;
  }
}
