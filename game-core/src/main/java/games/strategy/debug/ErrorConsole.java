package games.strategy.debug;

import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.ErrorHandler;
import games.strategy.ui.SwingAction;
import games.strategy.util.Interruptibles;

/**
 * A debug console window that displays the standard output and standard error streams.
 */
public final class ErrorConsole extends GenericConsole {
  private static final long serialVersionUID = -3489030525309243438L;
  private static ErrorConsole console;


  static {
    ClientSetting.SHOW_CONSOLE_ALWAYS.addSaveListener(newValue -> {
      if (newValue.equals(String.valueOf(true))) {
        ErrorConsole.showConsole();
      }
    });
  }

  private ErrorConsole() {
    super("TripleA Console");
  }

  /**
   * Makes the error console visible.
   */
  public static void showConsole() {
    getConsole().setVisible(true);
  }

  /**
   * Gets the singleton instance of the {@code ErrorConsole} class.
   *
   * @return An {@code ErrorConsole}.
   */
  public static ErrorConsole getConsole() {
    if (console == null) {
      createConsole();
    }
    return console;
  }

  /**
   * If not yet created, initializes the error console.
   */
  public static void createConsole() {
    Interruptibles.await(() -> SwingAction.invokeAndWait(() -> {
      console = new ErrorConsole();
      console.displayStandardOutput();
      console.displayStandardError();
      ErrorHandler.registerExceptionHandler();
    }));
  }

  @Override
  public GenericConsole getConsoleInstance() {
    return getConsole();
  }
}
