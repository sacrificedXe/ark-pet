/**
 * CloudTrail Spine → 7 WebP frames
 * node scripts/bake_cloud_trail.mjs
 *
 * Dependencies: @pixi-spine/runtime-4.1, canvas
 */

import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { createCanvas, Image } from 'canvas';
import * as Spine from '@pixi-spine/runtime-4.1';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ASSETS_DIR  = resolve(__dirname, '../app/src/main/assets/pet');
const CLOUD_DIR   = resolve(ASSETS_DIR, 'cloud_trail');
const OUT_DIR     = resolve(ASSETS_DIR);
const ANIMS       = ['Default', 'Interact', 'Move', 'Relax', 'Sit', 'Sleep', 'Special'];
const SIZE        = 512;
const FRAME_COUNT = 10;
const FPS         = 30;

// ── MiniAtlas: parse .atlas text format ─────────────────
// atlasAttachmentLoader.findRegion() needs objects with:
// { name, page, x, y, width, height, offsetX, offsetY,
//   originalWidth, originalHeight, rotate, index }
class MiniAtlas {
    constructor(atlasText, pngBuf) {
        this.pngBuf = pngBuf;
        this._regions = new Map();
        this.pw = 0;
        this.ph = 0;
        this._parse(atlasText);
    }
    _parse(text) {
        const lines = text.split(/\r?\n/);
        let i = 0;
        while (i < lines.length) {
            while (i < lines.length && lines[i].trim() === '') i++;
            if (i >= lines.length) break;
            i++;
            const page = {};
            while (i < lines.length && lines[i].trim() && !lines[i].startsWith('  ')) {
                const [k, ...v] = lines[i].trim().split(':');
                const n = v.join(':').trim().split(/\s+/).map(Number);
                if (k === 'size' && n.length === 2) { page.w = n[0]; page.h = n[1]; }
                i++;
            }
            i++;
            this.pw = page.w;
            this.ph = page.h;
            while (i < lines.length && lines[i].startsWith('  ') && lines[i].trim()) {
                const name = lines[i].trim();
                i++;
                const r = { name, page, x: 0, y: 0,
                    w: 0, h: 0,
                    ow: 0, oh: 0, ox: 0, oy: 0, ro: false };
                while (i < lines.length && lines[i].startsWith('  ') && lines[i].trim()) {
                    const [k, ...v] = lines[i].trim().split(':');
                    const n = v.join(':').trim().split(/\s+/).map(Number);
                    if      (k === 'xy')     { r.x = n[0]; r.y = n[1]; }
                    else if (k === 'size')   { r.w = n[0]; r.h = n[1]; }
                    else if (k === 'orig')   { r.ow = n[0]; r.oh = n[1]; }
                    else if (k === 'offset') { r.ox = n[0]; r.oy = n[1]; }
                    else if (k === 'rotate') { r.ro = n[0] !== 0; }
                    i++;
                }
                if (r.ow === 0) { r.ow = r.w; r.oh = r.h; }
                if (r.x !== 0 || r.y !== 0) this._regions.set(name, r);
            }
        }
    }
    findRegion(name) {
        const r = this._regions.get(name);
        if (!r) return null;
        return {
            name,
            page:              { width: r.pw, height: r.ph },
            x: r.x,            y: r.y,
            width: r.w,        height: r.h,
            originalWidth:  r.ow, originalHeight:  r.oh,
            offsetX: r.ox,     offsetY: r.oy,
            rotate: r.ro ? 1 : 0,
            index: -1,
        };
    }
}

// ── Canvas2D renderer ────────────────────────────────────
function render(ctx, skel, img) {
    const slots = skel.slots || [];
    for (const slot of slots) {
        const att = slot.attachment;
        if (!att || att.type !== 1 || !att.region) continue;
        const bone = slot.bone;
        const reg  = att.region;

        const bw = bone.worldX, bh = bone.worldY;
        const br = bone.worldRotation;
        const sa = bone.worldScaleX, sb = bone.worldScaleY;
        const ox = reg.offsetX || 0, oy = reg.offsetY || 0;
        const ow = reg.originalWidth  || reg.width;
        const oh = reg.originalHeight || reg.height;

        ctx.save();
        ctx.globalAlpha = (slot.color || {}).a !== undefined ? slot.color.a : 1;
        ctx.translate(bw, bh);
        ctx.rotate((br || 0) * Math.PI / 180);
        ctx.scale(sa * (att.scaleX || 1), sb * (att.scaleY || 1));
        ctx.drawImage(img, reg.x, reg.y, reg.width, reg.height, -ox, -oy, ow, oh);
        ctx.restore();
    }
}

// ── Main ─────────────────────────────────────────────────
async function main() {
    console.log('🎬 CloudTrail bake 開始');

    const atlasText = readFileSync(resolve(CLOUD_DIR, 'build_char_4165_ctrail.atlas'), 'utf-8');
    const skelBuf   = readFileSync(resolve(CLOUD_DIR, 'build_char_4165_ctrail.skel'));
    const pngBuf    = readFileSync(resolve(CLOUD_DIR, 'build_char_4165_ctrail.png'));

    const atlas  = new MiniAtlas(atlasText, pngBuf);
    const loader = new Spine.AtlasAttachmentLoader(atlas);

    let data;
    try {
        data = new Spine.SkeletonBinary(loader)
            .readSkeletonData(new Spine.BinaryInput(skelBuf));
    } catch (e) {
        console.log('  二進位解析失敗，嘗試 JSON...');
        data = new Spine.SkeletonJson(loader)
            .readSkeletonData(skelBuf.toString('utf-8'));
    }

    const found = data.animations.filter(a => ANIMS.includes(a.name));
    console.log(`  動畫: ${found.map(a => `${a.name}@${a.duration.toFixed(1)}s`).join(', ')}`);

    const img = new Image();
    img.src = pngBuf;
    await new Promise(r => { img.onload = r; img.onerror = () => {}; });

    const dt = 1 / FPS;
    for (const name of ANIMS) {
        const anim = data.animations.find(a => a.name === name);
        if (!anim) { console.log(`  ⚠️  ${name} 缺失`); continue; }

        const cvs = createCanvas(SIZE, SIZE);
        const ctx = cvs.getContext('2d');
        ctx.clearRect(0, 0, SIZE, SIZE);

        const skel  = new Spine.Skeleton(data);
        const state = new Spine.AnimationState(new Spine.AnimationStateData(data));
        skel.setToSetupPose();
        skel.updateWorldTransform();
        state.setAnimation(0, name, true);

        let t = 0;
        for (let f = 0; f < FRAME_COUNT && t < anim.duration; f++, t += dt) {
            state.update(dt);
            state.apply(skel);
            skel.updateWorldTransform();
            render(ctx, skel, img);
        }

        const buf = cvs.toBuffer('image/webp', { quality: 0.92 });
        const out = resolve(OUT_DIR, `cloud_trail_${name}.webp`);
        writeFileSync(out, buf);
        console.log(`  ✅ ${name}: ${(buf.length / 1024).toFixed(1)}KB`);
    }
    console.log('🎉 bake 完畢');
}

main().catch(e => { console.error('❌', e); process.exit(1); });
