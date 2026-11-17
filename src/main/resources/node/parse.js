// APlayer Backend - Meting parser
// 由 Java 端以子进程方式调用，参数全部通过环境变量传入，结果以 JSON 输出到 stdout。
// 输出格式：
// {
//   "ok": true,
//   "songs": [
//     { "id": "...", "name": "...", "artist": ["..."], "album": "...",
//       "audioUrl": "http://...", "coverUrl": "http://...", "lrc": "[00:00.000]...",
//       "br": 128 }
//   ]
// }
// 失败时：{ "ok": false, "error": "..." }，进程退出码 1。

import Meting from './meting/meting.esm.js';

function parseJson(value) {
  if (value == null) return value;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch (e) {
    return value;
  }
}

const SERVER = process.env.HS_SERVER || 'netease';
const TYPE = process.env.HS_TYPE || 'playlist';
const ID = process.env.HS_ID || '';
const COOKIE = process.env.HS_COOKIE || '';
const BITRATE = Number(process.env.HS_BITRATE || 320);
const PIC_SIZE = Number(process.env.HS_PIC_SIZE || 300);
const DOWNLOAD_LRC = process.env.HS_DOWNLOAD_LRC !== 'false';
const DOWNLOAD_COVER = process.env.HS_DOWNLOAD_COVER !== 'false';

function fail(message) {
  process.stdout.write(JSON.stringify({ ok: false, error: String(message) }));
  process.exit(1);
}

if (!ID) {
  fail('资源 ID 为空');
}

const meting = new Meting(SERVER);
if (COOKIE) {
  meting.cookie(COOKIE);
}
meting.format(true);

async function getSongIds() {
  let result;
  if (TYPE === 'playlist') {
    result = await meting.playlist(ID);
  } else if (TYPE === 'song') {
    result = await meting.song(ID);
  } else if (TYPE === 'album') {
    result = await meting.album(ID);
  } else {
    throw new Error('不支持的资源类型: ' + TYPE);
  }
  const parsed = parseJson(result);
  const list = Array.isArray(parsed) ? parsed : [parsed];
  return list.filter(Boolean).filter((s) => s && s.id != null);
}

try {
  const songsMeta = await getSongIds();
  if (!songsMeta.length) {
    fail('未解析到任何歌曲，请检查 ID / 平台 / Cookie 是否正确');
  }

  const songs = [];
  for (const meta of songsMeta) {
    const songId = meta.url_id ?? meta.id;
    const coverId = meta.pic_id;
    const lrcId = meta.lyric_id ?? meta.id;

    let audioUrl = null;
    let br = null;
    try {
      const u = parseJson(await meting.url(songId, BITRATE));
      if (u && u.url) {
        audioUrl = u.url;
        br = u.br != null ? Number(u.br) : null;
      }
    } catch (e) {
      // 单曲取音源失败不影响整批
    }

    let coverUrl = null;
    if (DOWNLOAD_COVER && coverId != null) {
      try {
        const p = parseJson(await meting.pic(coverId, PIC_SIZE));
        if (p && p.url) coverUrl = p.url;
      } catch (e) {
        // ignore
      }
    }

    let lrc = null;
    if (DOWNLOAD_LRC) {
      try {
        const l = parseJson(await meting.lyric(lrcId));
        if (l && l.lyric) lrc = l.lyric;
      } catch (e) {
        // ignore
      }
    }

    songs.push({
      id: String(meta.id),
      name: meta.name || '',
      artist: Array.isArray(meta.artist) ? meta.artist : (meta.artist ? [meta.artist] : []),
      album: meta.album || '',
      audioUrl,
      coverUrl,
      lrc,
      br,
    });
  }

  process.stdout.write(JSON.stringify({ ok: true, songs }));
} catch (e) {
  fail(e && e.message ? e.message : e);
}
