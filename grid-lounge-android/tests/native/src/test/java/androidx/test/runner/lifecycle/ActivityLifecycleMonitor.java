package androidx.test.runner.lifecycle;
import android.app.Activity; import java.util.Collection;
public interface ActivityLifecycleMonitor {
  void addLifecycleCallback(ActivityLifecycleCallback cb); void removeLifecycleCallback(ActivityLifecycleCallback cb);
  Stage getLifecycleStageOf(Activity a); Collection<Activity> getActivitiesInStage(Stage s);
}
