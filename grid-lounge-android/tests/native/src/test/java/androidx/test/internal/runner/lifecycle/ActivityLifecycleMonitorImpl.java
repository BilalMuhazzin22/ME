package androidx.test.internal.runner.lifecycle;
import android.app.Activity; import androidx.test.runner.lifecycle.*; import java.util.*;
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  private final Map<Activity, Stage> stages = new WeakHashMap<>(); private final List<ActivityLifecycleCallback> cbs = new ArrayList<>();
  public void addLifecycleCallback(ActivityLifecycleCallback cb) { cbs.add(cb); }
  public void removeLifecycleCallback(ActivityLifecycleCallback cb) { cbs.remove(cb); }
  public Stage getLifecycleStageOf(Activity a) { return stages.get(a); }
  public Collection<Activity> getActivitiesInStage(Stage s) { List<Activity> out = new ArrayList<>(); for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == s) out.add(e.getKey()); return out; }
  public void signalLifecycleChange(Stage s, Activity a) { stages.put(a, s); for (ActivityLifecycleCallback cb : new ArrayList<>(cbs)) cb.onActivityLifecycleChanged(a, s); }
}
