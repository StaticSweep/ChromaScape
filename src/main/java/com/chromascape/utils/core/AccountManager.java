package com.chromascape.utils.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton class to manage the selected account across the entire application. This provides a
 * centralized way to store and access the currently selected account.
 */
public class AccountManager {

  private static final Logger _logger = LoggerFactory.getLogger(AccountManager.class);

  // Static variable that persists across all classes
  private static String _selectedAccount = null;

  // Private constructor to prevent instantiation
  private AccountManager() {
    throw new UnsupportedOperationException(
        "AccountManager is a utility class and cannot be instantiated");
  }

  /**
   * Gets the currently selected account.
   *
   * @return The selected account name, or null if none is selected
   */
  public static String getSelectedAccount() {
    return _selectedAccount;
  }

  /**
   * Sets the selected account.
   *
   * @param account The account name to set
   */
  public static void setSelectedAccount(String account) {
    _selectedAccount = account;
    _logger.info("Selected account updated to: {}", account);
  }

  /** Clears the selected account. */
  public static void clearSelectedAccount() {
    _selectedAccount = null;
    _logger.info("Selected account cleared");
  }

  /**
   * Checks if an account is currently selected.
   *
   * @return true if an account is selected, false otherwise
   */
  public static boolean hasSelectedAccount() {
    return _selectedAccount != null && !_selectedAccount.trim().isEmpty();
  }

  /**
   * Gets the selected account or returns a default value if none is selected.
   *
   * @param defaultValue The default value to return if no account is selected
   * @return The selected account or the default value
   */
  public static String getSelectedAccountOrDefault(String defaultValue) {
    return hasSelectedAccount() ? _selectedAccount : defaultValue;
  }
}
