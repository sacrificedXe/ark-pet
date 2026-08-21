/**
 * 云迹 Spine → 7 组 WebP 烘焙脚本
 * 运行：npm i @pixi-spine/runtime-4.1 canvas && node scripts/bake_cloud_trail.mjs
 * 输出：app/src/main/assets/pet/cloud_trail_Default.webp 等
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { createCanvas } from 'canvas';
import * as Spine from '@pixi-spine/runtime-4.1';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR = resolve(__dirname, '../../app/src/main/assets/pet');
const CLOUD_TRAIL_DIR = resolve(ASSETS_DIR, 'cloud_trail');
const OUT_DIR = resolve(ASSETS_DIR);

// 7 组标准动画名
const ANIM_NAMES = ['Default', 'Interact', 'Move', 'Relax', 'Sit', 'Sleep', 'Special'];

// Minimal GL wrapper compatible with SpineSpriteRenderer expectations
class GLWrapper {
    constructor(gl) {
        this.gl = gl;
    }
    // Delegate all calls to the raw WebGL context
    getContext() { return this.gl; }
    // spine-webgl GL methods used by SpineSpriteRenderer
    createShader(type, source) { return this.gl.createShader(type); }
    compileShader(shader) { this.gl.compileShader(shader); }
    getShaderParameter(shader, pname) { return this.gl.getShaderParameter(shader, pname); }
    getShaderInfoLog(shader) { return this.gl.getShaderInfoLog(shader); }
    createProgram() { return this.gl.createProgram(); }
    attachShader(program, shader) { this.gl.attachShader(program, shader); }
    linkProgram(program) { this.gl.linkProgram(program); }
    getProgramParameter(program, pname) { return this.gl.getProgramParameter(program, pname); }
    getProgramInfoLog(program) { return this.gl.getProgramInfoLog(program); }
    useProgram(program) { this.gl.useProgram(program); }
    createBuffer() { return this.gl.createBuffer(); }
    bindBuffer(target, buffer) { this.gl.bindBuffer(target, buffer); }
    bufferData(target, data, usage) { this.gl.bufferData(target, data, usage); }
    enableVertexAttribArray(index) { this.gl.enableVertexAttribArray(index); }
    vertexAttribPointer(index, size, type, normalized, stride, offset) {
        this.gl.vertexAttribPointer(index, size, type, normalized, stride, offset);
    }
    drawArrays(mode, first, count) { this.gl.drawArrays(mode, first, count); }
    activeTexture(texture) { this.gl.activeTexture(texture); }
    bindTexture(target, texture) { this.gl.bindTexture(target, texture); }
    texImage2D(target, level, internalformat, format, type, pixels) {
        this.gl.texImage2D(target, level, internalformat, format, type, pixels);
    }
    texParameteri(target, pname, param) { this.gl.texParameteri(target, pname, param); }
    createTexture() { return this.gl.createTexture(); }
    deleteTexture(texture) { this.gl.deleteTexture(texture); }
    pixelStorei(pname, param) { this.gl.pixelStorei(pname, param); }
    clearColor(r, g, b, a) { this.gl.clearColor(r, g, b, a); }
    clear(mask) { this.gl.clear(mask); }
    viewport(x, y, width, height) { this.gl.viewport(x, y, width, height); }
    blendFunc(sfactor, dfactor) { this.gl.blendFunc(sfactor, dfactor); }
    enable(cap) { this.gl.enable(cap); }
    disable(cap) { this.gl.disable(cap); }
    getUniformLocation(program, name) { return this.gl.getUniformLocation(program, name); }
    uniform1i(location, v0) { this.gl.uniform1i(location, v0); }
    uniformMatrix4fv(location, transpose, value) { this.gl.uniformMatrix4fv(location, transpose, value); }
    getAttribLocation(program, name) { return this.gl.getAttribLocation(program, name); }
    // Canvas wrapper methods
    get canvas() { return this.gl.canvas; }
}

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
    const spineGL = new GLWrapper(gl);

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

        const frames = [];
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