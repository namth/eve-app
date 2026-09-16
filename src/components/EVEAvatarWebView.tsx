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

        @keyframes waveLeftSky {
            0%, 100% { transform: rotate(-200deg) translateY(2px) translateX(-6px); }
            50% { transform: rotate(-180deg) translateY(2px) translateX(-6px); }
        }
        .wave-left-anim {
            animation: waveLeftSky 0.65s ease-in-out infinite !important;
        }

        @keyframes waveRightSky {
            0%, 100% { transform: rotate(200deg) translateY(2px) translateX(6px); }
            50% { transform: rotate(180deg) translateY(2px) translateX(6px); }
        }
        .wave-right-anim {
            animation: waveRightSky 0.65s ease-in-out infinite !important;
        }

        @keyframes spinVisor3D {
            0% { transform: translateX(0px) scaleX(1); opacity: 1; }
            25% { transform: translateX(45px) scaleX(0.72); opacity: 1; }
            42% { transform: translateX(82px) scaleX(0.22); opacity: 0; }
            50% { transform: translateX(0px) scaleX(0); opacity: 0; }
            58% { transform: translateX(-82px) scaleX(0.22); opacity: 0; }
            75% { transform: translateX(-45px) scaleX(0.72); opacity: 1; }
            100% { transform: translateX(0px) scaleX(1); opacity: 1; }
        }
        .spin-visor-anim {
            animation: spinVisor3D 2.6s cubic-bezier(0.38, 0, 0.22, 1) !important;
        }

        @keyframes spinLeftArm3D {
            0% { transform: rotate(-10deg) translateY(18px) translateX(0px) scale(1); z-index: 25; }
            25% { transform: rotate(-3deg) translateY(16px) translateX(100px) scale(1.05); z-index: 25; }
            49% { transform: rotate(10deg) translateY(-14px) translateX(200px) scale(1); z-index: 25; }
            50% { transform: rotate(10deg) translateY(0px) translateX(200px) scale(0.96); z-index: 0; }
            75% { transform: rotate(3deg) translateY(8px) translateX(100px) scale(0.9); z-index: 0; }
            99% { transform: rotate(-10deg) translateY(18px) translateX(0px) scale(0.96); z-index: 0; }
            100% { transform: rotate(-10deg) translateY(18px) translateX(0px) scale(1); z-index: 20; }
        }
        .spin-left-arm-anim {
            animation: spinLeftArm3D 2.6s cubic-bezier(0.38, 0, 0.22, 1) !important;
        }

        @keyframes spinRightArm3D {
            0% { transform: rotate(10deg) translateY(18px) translateX(0px) scale(1); z-index: 0; }
            25% { transform: rotate(3deg) translateY(18px) translateX(-100px) scale(0.9); z-index: 0; }
            49% { transform: rotate(-10deg) translateY(-14px) translateX(-200px) scale(0.96); z-index: 0; }
            50% { transform: rotate(-10deg) translateY(0px) translateX(-200px) scale(1); z-index: 25; }
            75% { transform: rotate(-3deg) translateY(14px) translateX(-100px) scale(1.05); z-index: 25; }
            99% { transform: rotate(10deg) translateY(18px) translateX(0px) scale(1); z-index: 25; }
            100% { transform: rotate(10deg) translateY(18px) translateX(0px) scale(1); z-index: 20; }
        }
        .spin-right-arm-anim {
            animation: spinRightArm3D 2.6s cubic-bezier(0.38, 0, 0.22, 1) !important;
        }

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
                const happyRx = rx + 3;
                const topY = center.y - 30;
                const innerY = center.y - 6;
                const pLeft = rotatePoint(center.x - happyRx, center.y, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + happyRx, center.y, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, topY, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, innerY, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'smile' || state === 'wave-left' || state === 'wave-right' || state === 'spin-360') {
                const smileRx = rx + 1;
                const pLeft = rotatePoint(center.x - smileRx, center.y, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + smileRx, center.y, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, center.y - 18, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, center.y - 4, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'sad') {
                const sadRx = rx + 2;
                const sadRot = side === 'left' ? -16 : 16;
                const topY = center.y + 6;
                const bottomY = center.y + 16;
                const pLeft = rotatePoint(center.x - sadRx, center.y + 2, center.x, center.y, sadRot);
                const pRight = rotatePoint(center.x + sadRx, center.y + 2, center.x, center.y, sadRot);
                const pTop = rotatePoint(center.x, topY, center.x, center.y, sadRot);
                const pBottom = rotatePoint(center.x, bottomY, center.x, center.y, sadRot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'angry') {
                const angryRx = rx + 2;
                const angryRot = side === 'left' ? 22 : -22;
                const topY = center.y + 6;
                const bottomY = center.y + 16;
                const pLeft = rotatePoint(center.x - angryRx, center.y + 2, center.x, center.y, angryRot);
                const pRight = rotatePoint(center.x + angryRx, center.y + 2, center.x, center.y, angryRot);
                const pTop = rotatePoint(center.x, topY, center.x, center.y, angryRot);
                const pBottom = rotatePoint(center.x, bottomY, center.x, center.y, angryRot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'thinking') {
                return getEllipsePath(center.x, center.y, rx, 9, side === 'left' ? 22 : -22);
            } else if (state === 'sleeping' || state === 'blink-closed') {
                const sleepRx = rx + 1;
                const topY = center.y + 4;
                const bottomY = center.y + 10;
                const pLeft = rotatePoint(center.x - sleepRx, center.y + 2, center.x, center.y, rot);
                const pRight = rotatePoint(center.x + sleepRx, center.y + 2, center.x, center.y, rot);
                const pTop = rotatePoint(center.x, topY, center.x, center.y, rot);
                const pBottom = rotatePoint(center.x, bottomY, center.x, center.y, rot);
                return \`M \${pLeft.x} \${pLeft.y} Q \${pTop.x} \${pTop.y} \${pRight.x} \${pRight.y} Q \${pBottom.x} \${pBottom.y} \${pLeft.x} \${pLeft.y} Z\`;
            } else if (state === 'wakeup') {
                return getEllipsePath(center.x, center.y, rx + 3, ry + 5, rot);
            } else if (state === 'blink-half') {
                return getEllipsePath(center.x, center.y, rx, 4, rot);
            } else if (state === 'speaking') {
                return getEllipsePath(center.x, center.y, rx, Math.max(5, ry + param), rot);
            }
            return getEllipsePath(center.x, center.y, rx, ry, rot);
        }

        function updateEyes(speechVal = 0) {
            leftEyePath.setAttribute('d', getEyePath('left', currentExpression, speechVal));
            rightEyePath.setAttribute('d', getEyePath('right', currentExpression, speechVal));

            if (['happy', 'smile', 'sleeping', 'blink-closed', 'angry', 'sad', 'wave-left', 'wave-right', 'spin-360'].includes(currentExpression)) {
                const strokeWidth = (currentExpression === 'smile' || currentExpression === 'wave-left' || currentExpression === 'wave-right' || currentExpression === 'spin-360') ? '5' : '6';
                const strokeAttrs = { 'stroke': '#00f0ff', 'stroke-width': strokeWidth, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' };
                Object.keys(strokeAttrs).forEach(key => {
                    leftEyePath.setAttribute(key, strokeAttrs[key]);
                    rightEyePath.setAttribute(key, strokeAttrs[key]);
                });
            } else {
                ['stroke', 'stroke-width', 'stroke-linejoin', 'stroke-linecap'].forEach(attr => {
                    leftEyePath.removeAttribute(attr);
                    rightEyePath.removeAttribute(attr);
                });
            }
        }

        let speechPhase = 0;
        let speechAnimFrame = null;
        let blinkTimeout = null;
        let isBlinking = false;
        let actionTimeout = null;

        function resetActionStyles() {
            leftArm.classList.remove('wave-left-anim', 'spin-left-arm-anim', 'rub-chin-anim', 'slow-transition');
            rightArm.classList.remove('wave-right-anim', 'spin-right-arm-anim', 'slow-transition');
            eveVisor.classList.remove('spin-visor-anim');
            eveHead.classList.remove('giggle-anim');
        }

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
            if (actionTimeout) clearTimeout(actionTimeout);
            resetActionStyles();
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

                if (exp === 'thinking' || exp === 'angry') {
                    eveVisor.classList.remove('mt-3', 'mt-5');
                    eveVisor.classList.add('mt-7');
                } else if (exp === 'sad') {
                    eveVisor.classList.remove('mt-3', 'mt-7');
                    eveVisor.classList.add('mt-5');
                } else {
                    eveVisor.classList.remove('mt-7', 'mt-5');
                    eveVisor.classList.add('mt-3');
                }

                if (exp === 'happy') {
                    rightArm.style.transform = 'rotate(110deg) translateY(-25px) translateX(0px)';
                    eveHead.classList.add('giggle-anim');
                } else if (exp === 'smile') {
                    leftArm.style.transform = 'rotate(-15deg) translateY(18px) translateX(2px)';
                    rightArm.style.transform = 'rotate(15deg) translateY(18px) translateX(-2px)';
                } else if (exp === 'sad') {
                    leftArm.style.transform = 'rotate(-22deg) translateY(20px) translateX(-4px)';
                    rightArm.style.transform = 'rotate(22deg) translateY(20px) translateX(4px)';
                } else if (exp === 'angry') {
                    leftArm.style.transform = 'rotate(-20deg) translateY(22px) translateX(-4px)';
                    rightArm.style.transform = 'rotate(20deg) translateY(22px) translateX(4px)';
                    actionTimeout = setTimeout(() => { if (currentExpression === 'angry') setExpression('idle'); }, 3500);
                } else if (exp === 'thinking') {
                    leftArm.classList.add('slow-transition', 'rub-chin-anim');
                } else if (exp === 'speaking') {
                    leftArm.style.transform = 'rotate(-35deg) translateY(12px) translateX(0px)';
                    rightArm.style.transform = 'rotate(35deg) translateY(12px) translateX(0px)';
                    runSpeechEyePulse();
                } else if (exp === 'wave-left') {
                    leftArm.classList.add('wave-left-anim');
                    actionTimeout = setTimeout(() => { if (currentExpression === 'wave-left') setExpression('idle'); }, 3200);
                } else if (exp === 'wave-right') {
                    rightArm.classList.add('wave-right-anim');
                    actionTimeout = setTimeout(() => { if (currentExpression === 'wave-right') setExpression('idle'); }, 3200);
                } else if (exp === 'spin-360') {
                    eveVisor.classList.add('spin-visor-anim');
                    leftArm.classList.add('spin-left-arm-anim');
                    rightArm.classList.add('spin-right-arm-anim');
                    actionTimeout = setTimeout(() => { if (currentExpression === 'spin-360') setExpression('idle'); }, 2600);
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
                if (data.type === 'SET_EXPRESSION' || data.type === 'TRIGGER_ACTION') setExpression(data.payload);
                else if (data.type === 'WAKEUP') triggerWakeUp();
            } catch(err){}
        });
        document.addEventListener('message', (e) => {
            try {
                const data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                if (data.type === 'SET_EXPRESSION' || data.type === 'TRIGGER_ACTION') setExpression(data.payload);
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
