package androidx.test.platform.app;
import android.app.Instrumentation; import android.os.Bundle;
/** Minimal stand-in for androidx.test:monitor (Google Maven is unreachable here). */
public final class InstrumentationRegistry {
  private static Instrumentation inst; private static Bundle args = new Bundle();
  public static Instrumentation getInstrumentation() { return inst; }
  public static Bundle getArguments() { return args; }
  public static void registerInstance(Instrumentation i, Bundle b) { inst = i; args = b == null ? new Bundle() : b; }
}
