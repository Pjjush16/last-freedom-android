#!/bin/bash
# Easy AI 音色采样下载脚本（可选：项目已内置 soundfonts/ 目录，此脚本用于补全/重新下载）
# 用法：./fetch_samples.sh
cd "$(dirname "$0")"
mkdir -p soundfonts
BASE="https://raw.githubusercontent.com/gleitz/midi-js-soundfonts/master/FluidR3_GM"
INSTS="acoustic_grand_piano bright_acoustic_piano honkytonk_piano electric_piano_1 harpsichord violin viola cello contrabass string_ensemble_1 pizzicato_strings orchestral_harp trumpet trombone tuba french_horn brass_section flute piccolo clarinet oboe bassoon alto_sax tenor_sax acoustic_guitar_steel acoustic_guitar_nylon electric_guitar_clean overdriven_guitar distortion_guitar electric_bass_finger acoustic_bass slap_bass_1 synth_bass_1 drawbar_organ church_organ accordion tubular_bells music_box xylophone glockenspiel vibraphone celesta marimba timpani woodblock lead_2_sawtooth pad_1_new_age"
ok=0; fail=0
for i in $INSTS; do
  f="soundfonts/$i-mp3.js"
  if [ -s "$f" ] && [ $(stat -c%s "$f" 2>/dev/null || echo 0) -gt 100000 ]; then
    ok=$((ok+1)); continue
  fi
  echo "下载 $i ..."
  curl -sL --retry 3 --max-time 120 "$BASE/$i-mp3.js" -o "$f"
  if [ $(stat -c%s "$f" 2>/dev/null || echo 0) -gt 100000 ]; then ok=$((ok+1)); else fail=$((fail+1)); echo "  ✗ 失败: $i"; fi
done
echo "完成: $ok 个就绪, $fail 个失败"
[ $fail -gt 0 ] && echo "提示: 失败的音色会自动回退到物理建模合成，不影响使用"
