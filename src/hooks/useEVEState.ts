import { useState, useEffect, useRef, useCallback } from 'react';
import { EVEExpression } from '../types/api';

const IDLE_SLEEP_TIMEOUT =
  Number(process.env.EXPO_PUBLIC_IDLE_SLEEP_TIMEOUT) || 90000; // 90 giây

// Biểu cảm ngẫu nhiên khi fidget / tap
const RANDOM_EXPRESSIONS: EVEExpression[] = ['happy', 'smile', 'sad', 'thinking'];

const getRandom = (min: number, max: number) =>
  Math.floor(Math.random() * (max - min + 1)) + min;

const getRandomExpression = (): EVEExpression =>
  RANDOM_EXPRESSIONS[Math.floor(Math.random() * RANDOM_EXPRESSIONS.length)];

export const useEVEState = () => {
  const [expression, setExpressionState] = useState<EVEExpression>('idle');
  const [isWakingUp, setIsWakingUp] = useState(false);
  const sleepTimerRef = useRef<NodeJS.Timeout | null>(null);
  const fidgetTimerRef = useRef<NodeJS.Timeout | null>(null);
  const fidgetReturnRef = useRef<NodeJS.Timeout | null>(null);

  /**
   * Lên lịch 1 fidget ngẫu nhiên tiếp theo (interval ngẫu nhiên 3–9s)
   */
  const scheduleNextFidget = useCallback(() => {
    if (fidgetTimerRef.current) clearTimeout(fidgetTimerRef.current);
    const delay = getRandom(3000, 9000); // 3–9s trước khi fidget
    fidgetTimerRef.current = setTimeout(() => {
      setExpressionState((current) => {
        if (current !== 'idle') {
          // Nếu đang không idle thì bỏ qua, lên lịch lại
          scheduleNextFidget();
          return current;
        }
        const randomExp = getRandomExpression();
        const duration = getRandom(1000, 5000); // Kéo dài 1–5s
        console.log(`[useEVEState] [Fidget] ${randomExp} for ${duration}ms`);

        // Sau khi hết thời gian biểu cảm → về idle rồi lên lịch fidget tiếp
        if (fidgetReturnRef.current) clearTimeout(fidgetReturnRef.current);
        fidgetReturnRef.current = setTimeout(() => {
          setExpressionState((cur) => (cur === randomExp ? 'idle' : cur));
          scheduleNextFidget();
        }, duration);

        return randomExp;
      });
    }, delay);
  }, []);

  /**
   * Dừng toàn bộ fidget timers
   */
  const stopFidget = useCallback(() => {
    if (fidgetTimerRef.current) { clearTimeout(fidgetTimerRef.current); fidgetTimerRef.current = null; }
    if (fidgetReturnRef.current) { clearTimeout(fidgetReturnRef.current); fidgetReturnRef.current = null; }
  }, []);

  /**
   * Đặt lại bộ đếm tự động đi ngủ (90s)
   */
  const resetIdleTimer = useCallback(() => {
    if (sleepTimerRef.current) clearTimeout(sleepTimerRef.current);
    sleepTimerRef.current = setTimeout(() => {
      setExpressionState((current) => {
        if (current === 'idle') {
          console.log('[useEVEState] 90s Inactivity -> SLEEPING');
          stopFidget();
          return 'sleeping';
        }
        return current;
      });
    }, IDLE_SLEEP_TIMEOUT);
  }, [stopFidget]);

  /**
   * Cập nhật biểu cảm EVE
   */
  const setExpression = useCallback(
    (newExp: EVEExpression) => {
      // Waking up chỉ cho phép wakeup expression tiếp tục
      if (isWakingUp && newExp !== 'wakeup') return;
      console.log(`[useEVEState] Expression: ${newExp}`);
      setExpressionState(newExp);

      if (newExp === 'idle') {
        resetIdleTimer();
        scheduleNextFidget();
      } else if (newExp === 'sleeping') {
        if (sleepTimerRef.current) clearTimeout(sleepTimerRef.current);
        stopFidget();
      } else {
        // Đang thinking/speaking/emotion → dừng fidget, giữ sleep timer
        stopFidget();
      }
    },
    [isWakingUp, resetIdleTimer, scheduleNextFidget, stopFidget]
  );

  /**
   * Kích hoạt wakeup từ sleeping
   */
  const triggerWakeup = useCallback(() => {
    if (isWakingUp) return;
    console.log('[useEVEState] WAKEUP');
    setIsWakingUp(true);
    setExpressionState('wakeup');

    setTimeout(() => {
      setIsWakingUp(false);
      setExpressionState('idle');
      resetIdleTimer();
      scheduleNextFidget();
    }, 2200);
  }, [isWakingUp, resetIdleTimer, scheduleNextFidget]);

  /**
   * Xử lý chạm vào EVE
   * - Đang ngủ → wakeup
   * - Còn lại → ngay lập tức chuyển sang biểu cảm ngẫu nhiên 3s → idle
   */
  const handleCanvasTap = useCallback(() => {
    if (expression === 'sleeping') {
      triggerWakeup();
      return;
    }

    // Dừng fidget hiện tại, chuyển ngay sang random expression
    stopFidget();
    const randomExp = getRandomExpression();
    console.log(`[useEVEState] [Tap] Random expression: ${randomExp} (3s)`);
    setExpressionState(randomExp);
    resetIdleTimer();

    // Sau 3s → idle rồi lên lịch fidget lại
    if (fidgetReturnRef.current) clearTimeout(fidgetReturnRef.current);
    fidgetReturnRef.current = setTimeout(() => {
      setExpressionState('idle');
      scheduleNextFidget();
    }, 3000);
  }, [expression, triggerWakeup, stopFidget, resetIdleTimer, scheduleNextFidget]);

  useEffect(() => {
    resetIdleTimer();
    scheduleNextFidget();
    return () => {
      if (sleepTimerRef.current) clearTimeout(sleepTimerRef.current);
      stopFidget();
    };
  }, [resetIdleTimer, scheduleNextFidget, stopFidget]);

  return {
    expression,
    setExpression,
    triggerWakeup,
    handleCanvasTap,
    resetIdleTimer,
    isWakingUp,
  };
};
