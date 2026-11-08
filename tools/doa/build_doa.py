#!/usr/bin/env python3
"""
Bangun app/src/main/assets/doa.json untuk menu Doa Everest.

Sumber:
  - Teks Arab Al-Qur'an: Tanzil (tanzil.net), melalui dataset github.com/sutanlab/quran-api (MIT)
  - Terjemah Al-Qur'an: Kementerian Agama RI (quran.kemenag.go.id), dataset yang sama

Pemakaian:
  python3 build_doa.py <path/quran-api/data/quran.json> <output doa.json>

Skrip GAGAL bila potongan ayat di dalam doa (mis. Az-Zukhruf 13–14) tidak cocok huruf demi huruf
dengan mushaf di dataset, supaya salah ketik tidak lolos ke aplikasi.
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from content import KURSI, LATIN, RUQYAH_HADITH, RUQYAH_QURAN, TRAVEL  # noqa: E402

SURAH_NAMES = {}


def load(path):
    d = json.load(open(path, encoding="utf-8"))
    verses = {}
    for s in d["data"]:
        n = s["number"]
        SURAH_NAMES[n] = s["name"]["transliteration"]["id"]
        for v in s["verses"]:
            a = v["number"]["inSurah"]
            arab = v["text"]["arab"].replace("﻿", "").replace("۞", "").strip()
            verses[(n, a)] = {"arab": arab, "id": v["translation"]["id"].strip()}
    return verses


# Normalisasi untuk membandingkan huruf saja: buang harakat, tanda waqaf, tatweel; samakan bentuk alif/ya/ta marbutah
_HARAKAT = re.compile(r"[ؐ-ًؚ-ٰٟۖ-ۭـ]")


def letters(s):
    s = _HARAKAT.sub("", s)
    s = re.sub(r"[أإآٱ]", "ا", s)
    s = s.replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
    return re.sub(r"[^ء-ي]", "", s)


def check_fragment(verses, sura, aya, fragment):
    src = letters(verses[(sura, aya)]["arab"])
    frag = letters(fragment)
    if frag not in src:
        raise SystemExit(f"Potongan ayat {sura}:{aya} tidak cocok dengan mushaf:\n  ketik : {frag}\n  mushaf: {src}")


ARABIC_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def verse_obj(verses, sura, aya, with_latin=True):
    v = verses[(sura, aya)]
    return {
        "ref": f"{SURAH_NAMES[sura]}: {aya}",
        "number": aya,
        "numberAr": str(aya).translate(ARABIC_DIGITS),
        "arabic": v["arab"],
        "latin": LATIN.get((sura, aya)) if with_latin else None,
        "meaning": v["id"],
    }


def item(verses, d, category):
    out = {
        "id": d["id"],
        "category": category,
        "title": d["title"],
        "when": d.get("when"),
        "source": d["source"],
        "note": d.get("note"),
        "repeat": d.get("repeat"),
        "arabic": None,
        "latin": None,
        "meaning": None,
        "verses": [],
    }
    for sura, aya, frag in d.get("quran_fragments", []):
        check_fragment(verses, sura, aya, frag)
    if "quran" in d and "quran_part" in d:
        # Doa yang merupakan sebagian ayat: teks ditulis, lalu dicocokkan dengan mushaf
        sura, aya = d["quran"][0]
        check_fragment(verses, sura, aya, d["quran_part"])
        out.update(arabic=d["quran_part"], latin=d["latin"], meaning=d["meaning"])
    elif "quran" in d:
        out["verses"] = [verse_obj(verses, s, a) for s, a in d["quran"]]
    else:
        out.update(arabic=d["arabic"], latin=d["latin"], meaning=d["meaning"])
    return out


def main(src, dst):
    verses = load(src)
    travel = [item(verses, d, "perjalanan") for d in TRAVEL]
    kursi = [item(verses, d, "kursi") for d in KURSI]
    ruqyah = [
        {
            "id": rid,
            "category": "ruqyah",
            "title": title,
            "when": None,
            "source": "QS. " + title,
            "note": None,
            "repeat": None,
            "arabic": None,
            "latin": None,
            "meaning": None,
            "verses": [verse_obj(verses, s, a) for s, a in refs],
        }
        for rid, title, refs in RUQYAH_QURAN
    ] + [item(verses, d, "ruqyah") for d in RUQYAH_HADITH]

    data = {
        "version": 1,
        "categories": [
            {"id": "perjalanan", "title": "Doa Perjalanan & Bepergian", "subtitle": "Dari keluar rumah sampai pulang", "items": travel},
            {"id": "kursi", "title": "Ayat Kursi", "subtitle": "Al-Baqarah: 255", "items": kursi},
            {"id": "ruqyah", "title": "Ayat-ayat Ruqyah", "subtitle": "Ayat Al-Qur'an dan doa ruqyah dari hadits", "items": ruqyah},
        ],
        "credits": [
            "Teks Arab Al-Qur'an: Tanzil Project (tanzil.net), disalin tanpa perubahan",
            "Terjemah Al-Qur'an: Kementerian Agama Republik Indonesia (quran.kemenag.go.id)",
            "Data ayat melalui github.com/sutanlab/quran-api (MIT)",
            "Transliterasi latin dan terjemah doa hadits: tim Everest — mohon ditinjau ulang oleh ustadz",
        ],
    }
    # Validasi struktur
    ids = set()
    for c in data["categories"]:
        for it in c["items"]:
            assert it["id"] not in ids, f"id ganda: {it['id']}"
            ids.add(it["id"])
            if it["verses"]:
                for v in it["verses"]:
                    assert v["arabic"] and v["meaning"], it["id"]
            else:
                assert it["arabic"] and it["meaning"] and it["latin"], it["id"]
                assert re.search(r"[؀-ۿ]", it["arabic"]), f"teks Arab kosong: {it['id']}"
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    Path(dst).write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    n_items = sum(len(c["items"]) for c in data["categories"])
    n_verses = sum(len(i["verses"]) for c in data["categories"] for i in c["items"])
    n_latin = sum(1 for c in data["categories"] for i in c["items"] for v in i["verses"] if v["latin"])
    print(f"OK: {n_items} bacaan, {n_verses} ayat ({n_latin} dengan latin) → {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
