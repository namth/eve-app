import React, { useRef, useEffect } from 'react';
import { StyleSheet, View, Platform } from 'react-native';
import { WebView } from 'react-native-webview';
import { EVEExpression } from '../types/api';

interface Props {
  expression: EVEExpression;
  onTapCanvas?: () => void;
  onExpressionChanged?: (exp: EVEExpression) => void;
}

// Inline HTML snippet derived from eve_robot_interface.html for reliable WebView loading across iOS/Android
const EVE_HTML_CONTENT = `
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>EVE AI Avatar</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;600;700&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Plus Jakarta Sans', sans-serif;
            background: transparent;
            user-select: none;
            -webkit-user-select: none;
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            width: 100vw;
        }
        @keyframes hover {
            0% { transform: translateY(0px); }
            50% { transform: translateY(-12px); }
            100% { transform: translateY(0px); }
        }
        @keyframes floorGlow {
            0% { transform: translateX(-50%) scale(1); opacity: 0.75; }
            50% { transform: translateX(-50%) scale(0.85); opacity: 0.55; }
            100% { transform: translateX(-50%) scale(1); opacity: 0.75; }
        }
        .eve-character { animation: hover 4s ease-in-out infinite; }
        #eve-avatar { transition: filter 2s ease-in-out; }
        @keyframes sleepAuraPulse {
            0%, 100% { filter: drop-shadow(0 0 0px rgba(255, 255, 255, 0)); }
            50% { filter: drop-shadow(0 0 20px rgba(255, 255, 255, 0.95)); }
        }
        .visor-sleep-glow {
            background: rgba(255, 255, 255, 0.75);
            box-shadow: 0 0 25px rgba(255, 255, 255, 0.9), inset 0 0 15px rgba(255, 255, 255, 0.9);
            opacity: 0;
            pointer-events: none;
        }
        @keyframes visorGlowPulse { 0%, 100% { opacity: 0; } 50% { opacity: 0.35; } }
        .aura-pulse { animation: sleepAuraPulse 3s ease-in-out infinite; }
        .aura-pulse .visor-sleep-glow { animation: visorGlowPulse 3s ease-in-out infinite; }
        .eve-head {
            background: linear-gradient(135deg, #ffffff 0%, #f1f5f9 45%, #cbd5e1 100%);
            box-shadow: inset 10px 10px 20px rgba(255, 255, 255, 0.9), inset -10px -10px 25px rgba(100, 116, 139, 0.25), 0 15px 35px rgba(0, 0, 0, 0.35);
            position: relative;
            border-radius: 50% 50% 50% 50% / 66% 66% 40% 40%;
        }
        .eve-visor {
            background: radial-gradient(circle at 50% 30%, #001c3d 0%, #020617 100%);
            box-shadow: inset 0 10px 20px rgba(0, 0, 0, 0.95), inset 0 -5px 15px rgba(255, 255, 255, 0.05), 0 0 0 2px rgba(255, 255, 255, 0.1);
            overflow: hidden;
            transition: margin-top 0.3s ease-in-out;
        }
        .visor-reflection {
            position: absolute; top: 2px; left: 15%; width: 70%; height: 30%;
            background: linear-gradient(to bottom, rgba(255, 255, 255, 0.12) 0%, rgba(255, 255, 255, 0) 100%);
            border-radius: 0 0 100px 100px / 0 0 35px 35px;
            pointer-events: none;
        }
        .eve-body {
            background: linear-gradient(135deg, #ffffff 0%, #f8fafc 40%, #94a3b8 100%);
            box-shadow: inset 15px 15px 30px rgba(255, 255, 255, 0.95), inset -15px -15px 35px rgba(100, 116, 139, 0.25), 0 25px 45px rgba(0, 0, 0, 0.4);
            border-radius: 80px 80px 100px 100px / 25px 25px 230px 230px;
        }
        .eve-arm {
            background: linear-gradient(135deg, #ffffff 0%, #f1f5f9 50%, #94a3b8 100%);
            box-shadow: inset 6px 6px 12px rgba(255, 255, 255, 0.9), inset -6px -6px 12px rgba(100, 116, 139, 0.2), 0 15px 25px rgba(0, 0, 0, 0.3);
            border-radius: 50% 50% 50% 50% / 40% 40% 60% 60%;
            transition: transform 0.5s cubic-bezier(0.175, 0.885, 0.32, 1.275), opacity 1.5s ease-in-out;
            z-index: 20;
        }
        @keyframes startle {
            0% { transform: translateY(0px) scale(1); }
            20% { transform: translateY(-16px) scale(1.04); }
            45% { transform: translateY(3px) scale(0.98); }
            70% { transform: translateY(-3px) scale(1.01); }
            100% { transform: translateY(0px) scale(1); }
        }
        @keyframes rubChin {
            0% { transform: rotate(-122deg) translateY(-41px) translateX(-25px); }
            50% { transform: rotate(-118deg) translateY(-32px) translateX(-25px); }
            100% { transform: rotate(-122deg) translateY(-41px) translateX(-25px); }
        }
        .rub-chin-anim { animation: rubChin 2s ease-in-out infinite !important; }
        .slow-transition { transition: transform 1.0s ease-in-out !important; }
        .show-bubble { opacity: 1 !important; transform: scale(1) !important; pointer-events: auto !important; }
        @keyframes bubbleFloat { 0%, 100% { transform: translateY(0px); } 50% { transform: translateY(-5px); } }
        .bubble-inner { animation: bubbleFloat 2s ease-in-out infinite; }
        @keyframes giggle {
            0%, 100% { transform: translateY(0px) rotate(0deg); }
            20% { transform: translateY(-6px) rotate(-3deg); }
            40% { transform: translateY(2px) rotate(2deg); }
            60% { transform: translateY(-5px) rotate(-2deg); }
            80% { transform: translateY(1px) rotate(1deg); }
        }
        .giggle-anim { animation: giggle 0.38s ease-in-out infinite !important; }
        @keyframes presentLeftArm {
            0%, 100% { transform: rotate(-25deg) translateY(14px) translateX(0px); }
            50% { transform: rotate(-45deg) translateY(2px) translateX(-6px); }
        }
        @keyframes presentRightArm {
            0%, 100% { transform: rotate(42deg) translateY(4px) translateX(4px); }
            50% { transform: rotate(22deg) translateY(16px) translateX(0px); }
        }
        .speaking-left-arm { animation: presentLeftArm 1.6s ease-in-out infinite !important; }
        .speaking-right-arm { animation: presentRightArm 2.0s ease-in-out infinite !important; }
        @keyframes hoverFloat {
            0%, 100% { transform: translateY(0px); }
            50% { transform: translateY(-16px); }
        }
        .eve-character {
            animation: hoverFloat 4s ease-in-out infinite;
            filter: drop-shadow(0 22px 28px rgba(0, 240, 255, 0.3)) drop-shadow(0 35px 50px rgba(0, 0, 0, 0.75));
        }
        @keyframes floorShadow {
            0%, 100% { transform: translateX(-50%) scale(1); opacity: 0.85; }
            50% { transform: translateX(-50%) scale(0.65); opacity: 0.35; }
        }
        .floor-light {
            background: radial-gradient(ellipse at center, rgba(0, 240, 255, 0.85) 0%, rgba(0, 240, 255, 0.4) 45%, rgba(0, 240, 255, 0) 80%);
            animation: floorShadow 4s ease-in-out infinite;
        }
    </style>
</head>
<body style="margin: 0; padding: 0; background: transparent; user-select: none; -webkit-user-select: none; overflow: visible; display: flex; align-items: center; justify-content: center; width: 100%; height: 100%;">
    <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; position: relative; width: 100%; height: 420px; overflow: visible;">
        <div class="eve-character flex flex-col items-center relative z-10 w-full">
            <div id="eve-avatar" class="flex flex-col items-center relative">
                <div id="eve-head" class="eve-head w-[180px] h-[150px] flex items-center justify-center mb-1 relative">
                    <div id="eve-visor" class="eve-visor w-[144px] h-[100px] rounded-[50%_50%_50%_50%_/_66%_66%_42%_42%] mt-3 relative flex items-center justify-center">
                        <div class="visor-reflection"></div>
                        <div class="visor-sleep-glow absolute inset-0 rounded-[50%_50%_50%_50%_/_66%_66%_42%_42%]"></div>
                        <svg id="eye-svg" class="neon-eye-glow w-full h-full absolute inset-0 z-10" viewBox="0 0 184 116">
                            <path id="left-eye-path" fill="#00f0ff" class="scanline-mask" />
                            <path id="right-eye-path" fill="#00f0ff" class="scanline-mask" />
                        </svg>
                    </div>
                </div>
                <div class="relative w-[280px] h-[210px] flex justify-center">
                    <div id="left-arm" class="eve-arm w-[32px] h-[150px] absolute left-[26px] top-[16px] origin-top -rotate-[10deg]"></div>
                    <div class="eve-body w-[165px] h-[210px]"></div>
                    <div id="right-arm" class="eve-arm w-[32px] h-[150px] absolute right-[26px] top-[16px] origin-top rotate-[10deg]"></div>
                </div>
            </div>
        </div>
        <div class="floor-light" style="position: absolute; bottom: -5px; left: 50%; width: 110px; height: 18px; border-radius: 50%; z-index: 0;"></div>
    </div>

    <script>
        const leftArm = document.getElementById('left-arm');
        const rightArm = document.getElementById('right-arm');
        const eveVisor = document.getElementById('eve-visor');
        const eveHead = document.getElementById('eve-head');
        const leftEyePath = document.getElementById('left-eye-path');
        const rightEyePath = document.getElementById('right-eye-path');

        let currentExpression = 'idle';
        let isWakingUp = false;
        const leftCenter = { x: 55, y: 58 };
        const rightCenter = { x: 129, y: 58 };
        const baseRadius = { rx: 24, ry: 16 };

        function rotatePoint(x, y, cx, cy, degrees) {
            const radians = (degrees * Math.PI) / 180;
            return { x: cx + (x - cx) * Math.cos(radians) - (y - cy) * Math.sin(radians), y: cy + (x - cx) * Math.sin(radians) + (y - cy) * Math.cos(radians) };
        }

        function getEllipsePath(cx, cy, rx, ry, degrees) {
            const radians = (degrees * Math.PI) / 180;
            const cos = Math.cos(radians), sin = Math.sin(radians);
            const x1 = cx + rx * cos, y1 = cy + rx * sin;
            const x2 = cx - rx * cos, y2 = cy - rx * sin;
            return \`M \${x1} \${y1} A \${rx} \${ry} \${degrees} 0 0 \${x2} \${y2} A \${rx} \${ry} \${degrees} 0 0 \${x1} \${y1} Z\`;
        }

        function getEyePath(side, state, param = 0) {
            const center = side === 'left' ? leftCenter : rightCenter;
            const rx = baseRadius.rx, ry = baseRadius.ry;
            const rot = side === 'left' ? 12 : -12;
            if (state === 'happy') {
                const pLeft = rotatePoint(center.x - rx - 3, center.y, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + rx + 3, center.y, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, center.y - 30, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, center.y - 6, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'smile') {
                const pLeft = rotatePoint(center.x - rx - 1, center.y, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + rx + 1, center.y, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, center.y - 20, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, center.y - 4, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'sad') {
                const sadRx = rx + 3;
                const innerX = side === 'left' ? center.x + sadRx : center.x - sadRx;
                const outerX = side === 'left' ? center.x - sadRx : center.x + sadRx;
                
                const pInnerTop = rotatePoint(innerX, center.y - 14, center.x, center.y, rot);
                const pOuterTop = rotatePoint(outerX, center.y + 8, center.x, center.y, rot);
                const pOuterBottom = rotatePoint(outerX, center.y + 18, center.x, center.y, rot);
                const pInnerBottom = rotatePoint(innerX, center.y + 10, center.x, center.y, rot);
                const pMidTop = rotatePoint(center.x, center.y - 8, center.x, center.y, rot);

                return \`M \${pInnerTop.x} \${pInnerTop.y} Q \${pMidTop.x} \${pMidTop.y} \${pOuterTop.x} \${pOuterTop.y} Q \${outerX} \${center.y + 16} \${pOuterBottom.x} \${pOuterBottom.y} Q \${center.x} \${center.y + 18} \${pInnerBottom.x} \${pInnerBottom.y} Z\`;
            } else if (state === 'thinking') {
                return getEllipsePath(center.x, center.y, rx, 9, side === 'left' ? 22 : -22);
            } else if (state === 'sleeping' || state === 'blink-closed') {
                const pLeft = rotatePoint(center.x - rx - 1, center.y + 2, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + rx + 1, center.y + 2, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, center.y + 6, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, center.y + 16, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'wakeup') {
                return getEllipsePath(center.x, center.y, rx + 3, ry + 5, rot);
            } else if (state === 'speaking') {
                return getEllipsePath(center.x, center.y, rx, Math.max(5, ry + param), rot);
            }
            return getEllipsePath(center.x, center.y, rx, ry, rot);
        }

        function updateEyes(speechVal = 0) {
            leftEyePath.setAttribute('d', getEyePath('left', currentExpression, speechVal));
            rightEyePath.setAttribute('d', getEyePath('right', currentExpression, speechVal));
        }

        let speechPhase = 0;
        let speechAnimFrame = null;
        let blinkTimeout = null;
        let isBlinking = false;

        function startNaturalBlinking() {
            const blinkLoop = () => {
                if (['idle', 'thinking', 'speaking'].includes(currentExpression) && !isBlinking) {
                    isBlinking = true;
                    const savedState = currentExpression;
                    leftEyePath.setAttribute('d', getEyePath('left', 'blink-half'));
                    rightEyePath.setAttribute('d', getEyePath('right', 'blink-half'));
                    
                    setTimeout(() => {
                        if (currentExpression === savedState) {
                            leftEyePath.setAttribute('d', getEyePath('left', 'blink-closed'));
                            rightEyePath.setAttribute('d', getEyePath('right', 'blink-closed'));
                            
                            setTimeout(() => {
                                isBlinking = false;
                                if (currentExpression !== 'speaking') {
                                    updateEyes();
                                }
                            }, 80);
                        } else {
                            isBlinking = false;
                        }
                    }, 40);
                }
                blinkTimeout = setTimeout(blinkLoop, 3000 + Math.random() * 3500);
            };
            blinkTimeout = setTimeout(blinkLoop, 3500);
        }

        function runSpeechEyePulse() {
            if (currentExpression === 'speaking') {
                speechPhase += 0.18;
                if (!isBlinking) {
                    const targetAmp = Math.sin(speechPhase) * Math.cos(speechPhase * 0.7) * 9 + (Math.random() * 4 - 2);
                    leftEyePath.setAttribute('d', getEyePath('left', 'speaking', targetAmp));
                    rightEyePath.setAttribute('d', getEyePath('right', 'speaking', targetAmp));
                }
                speechAnimFrame = requestAnimationFrame(runSpeechEyePulse);
            }
        }

        function setExpression(exp) {
            currentExpression = exp;
            if (speechAnimFrame) cancelAnimationFrame(speechAnimFrame);
            leftArm.classList.remove('rub-chin-anim', 'slow-transition');
            rightArm.classList.remove('slow-transition');
            eveHead.classList.remove('giggle-anim');
            const avatar = document.getElementById('eve-avatar');
            if (avatar) avatar.classList.remove('aura-pulse');

            if (exp === 'sleeping') {
                if (avatar) avatar.classList.add('aura-pulse');
                leftArm.classList.add('slow-transition');
                rightArm.classList.add('slow-transition');
                leftArm.style.transform = 'rotate(-15deg) translateY(20px) translateX(12px)';
                rightArm.style.transform = 'rotate(15deg) translateY(20px) translateX(-12px)';
                
                leftEyePath.style.transition = 'opacity 1s ease-in-out';
                rightEyePath.style.transition = 'opacity 1s ease-in-out';
                leftArm.style.opacity = '0';
                rightArm.style.opacity = '0';
                leftEyePath.style.opacity = '0';
                rightEyePath.style.opacity = '0';
            } else {
                leftArm.style.transition = 'none';
                rightArm.style.transition = 'none';
                leftEyePath.style.transition = 'none';
                rightEyePath.style.transition = 'none';

                leftEyePath.style.opacity = '1'; rightEyePath.style.opacity = '1';
                leftArm.style.opacity = '1'; rightArm.style.opacity = '1';

                leftArm.style.transform = 'rotate(-10deg) translateY(20px) translateX(0px)';
                rightArm.style.transform = 'rotate(10deg) translateY(20px) translateX(0px)';

                if (exp === 'happy') {
                    rightArm.style.transform = 'rotate(110deg) translateY(-25px) translateX(0px)';
                    eveHead.classList.add('giggle-anim');
                } else if (exp === 'smile') {
                    leftArm.style.transform = 'rotate(-10deg) translateY(20px) translateX(0px)';
                    rightArm.style.transform = 'rotate(10deg) translateY(20px) translateX(0px)';
                } else if (exp === 'sad') {
                    leftArm.style.transform = 'rotate(-22deg) translateY(20px) translateX(-4px)';
                    rightArm.style.transform = 'rotate(22deg) translateY(20px) translateX(4px)';
                } else if (exp === 'thinking') {
                    leftArm.classList.add('slow-transition', 'rub-chin-anim');
                } else if (exp === 'speaking') {
                    leftArm.style.transform = 'rotate(-35deg) translateY(12px) translateX(0px)';
                    rightArm.style.transform = 'rotate(35deg) translateY(12px) translateX(0px)';
                    runSpeechEyePulse();
                }

                setTimeout(() => {
                    if (currentExpression !== 'sleeping') {
                        leftArm.style.transition = '';
                        rightArm.style.transition = '';
                        leftEyePath.style.transition = '';
                        rightEyePath.style.transition = '';
                    }
                }, 20);
            }
            updateEyes();
        }

        startNaturalBlinking();

        function triggerWakeUp() {
            setExpression('wakeup');
            const avatar = document.getElementById('eve-avatar');
            if (avatar) avatar.classList.remove('aura-pulse');
            const eveChar = document.querySelector('.eve-character');
            if (eveChar) {
                eveChar.style.animation = 'startle 0.6s ease-out';
                setTimeout(() => { eveChar.style.animation = 'hover 4s ease-in-out infinite'; }, 600);
            }
            updateEyes();
            setTimeout(() => { setExpression('idle'); }, 2200);
        }

        document.body.addEventListener('click', () => {
            if (window.ReactNativeWebView) {
                window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'CANVAS_TAP', currentExpression }));
            }
        });

        window.addEventListener('message', (e) => {
            try {
                const data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                if (data.type === 'SET_EXPRESSION') setExpression(data.payload);
                else if (data.type === 'WAKEUP') triggerWakeUp();
            } catch(err){}
        });
        document.addEventListener('message', (e) => {
            try {
                const data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                if (data.type === 'SET_EXPRESSION') setExpression(data.payload);
                else if (data.type === 'WAKEUP') triggerWakeUp();
            } catch(err){}
        });

        setExpression('idle');
    </script>
</body>
</html>
`;

export const EVEAvatarWebView: React.FC<Props> = ({
  expression,
  onTapCanvas,
  onExpressionChanged,
}) => {
  const webViewRef = useRef<WebView>(null);

  useEffect(() => {
    if (webViewRef.current) {
      if (expression === 'wakeup') {
        webViewRef.current.postMessage(JSON.stringify({ type: 'WAKEUP' }));
      } else {
        webViewRef.current.postMessage(
          JSON.stringify({ type: 'SET_EXPRESSION', payload: expression })
        );
      }
    }
  }, [expression]);

  const handleMessage = (event: any) => {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (data.type === 'CANVAS_TAP') {
        if (onTapCanvas) onTapCanvas();
      }
    } catch (e) {
      console.log('WebView message error:', e);
    }
  };

  return (
    <View style={styles.container}>
      <WebView
        ref={webViewRef}
        originWhitelist={['*']}
        source={{ html: EVE_HTML_CONTENT }}
        style={styles.webview}
        scrollEnabled={false}
        onMessage={handleMessage}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        allowFileAccess={true}
        allowUniversalAccessFromFileURLs={true}
        mixedContentMode="always"
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    width: '100%',
    height: 440,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'visible',
  },
  webview: {
    width: 360,
    height: 440,
    backgroundColor: 'transparent',
  },
});
