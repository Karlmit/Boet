import { useCallback, useEffect, useRef, useState } from 'react';

// Screen Wake Lock — the web equivalent of Android's FLAG_KEEP_SCREEN_ON
// lightbulb toggle on the recipe screen. The browser silently releases the
// sentinel when the tab is hidden, so an enabled lock is re-requested on
// visibilitychange. Unsupported browsers (e.g. Firefox desktop) report
// supported=false so the toggle can be hidden entirely.
export function useWakeLock() {
  const supported = typeof navigator !== 'undefined' && 'wakeLock' in navigator;
  const [active, setActive] = useState(false);
  const sentinelRef = useRef<WakeLockSentinel | null>(null);
  const wantedRef = useRef(false);

  const request = useCallback(async () => {
    try {
      const sentinel = await navigator.wakeLock.request('screen');
      sentinel.addEventListener('release', () => {
        if (sentinelRef.current === sentinel) sentinelRef.current = null;
      });
      sentinelRef.current = sentinel;
      setActive(true);
    } catch {
      // Denied (battery saver etc.) — reflect reality in the toggle.
      wantedRef.current = false;
      setActive(false);
    }
  }, []);

  const toggle = useCallback(async () => {
    if (!supported) return;
    if (wantedRef.current) {
      wantedRef.current = false;
      await sentinelRef.current?.release().catch(() => {});
      sentinelRef.current = null;
      setActive(false);
    } else {
      wantedRef.current = true;
      await request();
    }
  }, [supported, request]);

  useEffect(() => {
    if (!supported) return;
    const onVisibility = () => {
      if (wantedRef.current && document.visibilityState === 'visible' && !sentinelRef.current) {
        request();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      wantedRef.current = false;
      sentinelRef.current?.release().catch(() => {});
      sentinelRef.current = null;
    };
  }, [supported, request]);

  return { supported, active, toggle };
}
