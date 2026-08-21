/**
 * 云迹 Spine → 7 组 WebP 烘焙脚本
 * 运行：npm i @pixi-spine/runtime-4.1 spine-webgl canvas && node scripts/bake_cloud_trail.mjs
 * 输出：app/src/main/assets/pet/cloud_trail_Default.webp 等
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { createCanvas } from 'canvas';
import { Spine } from '@pixi-spine/runtime-4.1';
import { GL } from 'spine-webgl';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR = resolve(__dirname, '../../app/src/main/assets/pet');
const CLOUD_TRAIL_DIR = resolve(ASSETS_DIR, 'cloud_trail');
const OUT_DIR = resolve(ASSETS_DIR);

// 7 组标准动画名
const ANIM_NAMES = ['Default', 'Interact', 'Move', 'Relax', 'Sit', 'Sleep', 'Special'];

async function main() {
    console.log('🎞️  云迹 Spine → WebP 烘焙开始');

    // 1. 读取 Spine 文件
    const atlasText = readFileSync(resolve(CLOUD_TRAIL_DIR, 'build_char_4165_ctrail.atlas'), 'utf-8');
    const skelBytes = readFileSync(resolve(CLOUD_TRAIL_DIR, 'build_char_4165_ctrail.skel'));
    const pngBuf = readFileSync(resolve(CLOUD_TRAIL_DIR, 'build_char_4165_ctrail.png'));

    // 2. 创建 Spine 实例
    const atlas = new Spine.Atlas(atlasText, (path) => pngBuf);
    const atlasLoader = new Spine.AtlasAttachmentLoader(atlas);
    const skeletonJson = new Spine.SkeletonJson(atlasLoader);
    const skeletonData = skeletonJson.readSkeletonData(new Spine.ByteArrayInputStream(skelBytes));

    // 3. 创建 WebGL 上下文（离屏 canvas）
    const canvas = createCanvas(512, 512);
    const gl = canvas.getContext('webgl2', { alpha: true, preserveDrawingBuffer: true });
    if (!gl) throw new Error('WebGL2 不可用');
    const spineGL = new GL(gl);

    // 4. 遍历动画烘焙
    for (const animName of ANIM_NAMES) {
        const anim = skeletonData.findAnimation(animName);
        if (!anim) {
            console.log(`⚠️  动画 ${animName} 不存在，跳过`);
            continue;
        }
        console.log(`🔄 烘焙 ${animName} (${anim.duration.toFixed(2)}s)...`);

        const skeleton = new Spine.Skeleton(skeletonData);
        skeleton.setToSetupPose();
        skeleton.updateWorldTransform();

        const state = new Spine.AnimationState(new Spine.AnimationStateData(skeletonData));
        state.setAnimation(0, animName, true);

        const frames: Buffer[] = [];
        const fps = 30;
        const frameTime = 1 / fps;
        let elapsed = 0;
        const duration = anim.duration;

        while (elapsed < duration + 0.01) { // 多跑一帧确保循环
            state.update(frameTime);
            state.apply(skeleton);
            skeleton.updateWorldTransform();

            // 渲染
            gl.clearColor(0, 0, 0, 0);
            gl.clear(gl.COLOR_BUFFER_BIT);
            const renderer = new Spine.SpineSpriteRenderer(spineGL);
            renderer.draw(skeleton);

            // 读取像素
            const pixels = new Uint8Array(512 * 512 * 4);
            gl.readPixels(0, 0, 512, 512, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
            frames.push(Buffer.from(pixels));
            elapsed += frameTime;
        }

        // 5. 写 WebP（使用 canvas 编码）
        const webpCanvas = createCanvas(512, 512);
        const ctx = webpCanvas.getContext('2d');
        const frameBuf = frames[0]; // 只取第一帧做静态预览，动画 WebP 需额外库
        const imgData = ctx.createImageData(512, 512);
        imgData.data.set(new Uint8ClampedArray(frameBuf));
        ctx.putImageData(imgData, 0, 0);

        const outPath = resolve(OUT_DIR, `cloud_trail_${animName}.webp`);
        const webpBuf = webpCanvas.toBuffer('image/webp', { quality: 0.85 });
        writeFileSync(outPath, webpBuf);
        console.log(`✅ ${outPath} (${(webpBuf.length/1024).toFixed(1)}KB)`);
    }

    console.log('🎉 云迹烘焙完成');
}

main().catch(e => { console.error(e); process.exit(1); });
