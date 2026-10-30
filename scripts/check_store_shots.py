"""Play needs opaque RGB screenshots: phone sides 320..3840, ratio <=2:1; wear square >=384."""
import sys
from pathlib import Path

from PIL import Image


def check(file, image_type):
    try:
        with Image.open(file) as image:
            image.load()
            if "A" in image.getbands() or "transparency" in image.info:
                if image.convert("RGBA").getchannel("A").getextrema()[0] < 255:
                    raise ValueError("transparent pixels")
            rgb = image.convert("RGB")
        rgb.save(file)
        width, height = rgb.size
        if image_type == "phoneScreenshots":
            if not (320 <= width <= 3840 and 320 <= height <= 3840):
                raise ValueError("phone sides must be within 320..3840")
            if max(width, height) / min(width, height) > 2:
                raise ValueError("phone aspect ratio exceeds 2:1")
        elif width != height or width < 384:
            raise ValueError("wear screenshots must be square and at least 384 pixels")
        print(f"PASS {file} {width}x{height}")
        return True
    except (OSError, ValueError) as error:
        print(f"FAIL {file} {error}")
        return False


def main():
    if len(sys.argv) < 2:
        print("Usage: check_store_shots.py <dir>...")
        return 1
    passed = True
    for directory in map(Path, sys.argv[1:]):
        files = [
            (file, image_type)
            for image_type in ("phoneScreenshots", "wearScreenshots")
            for file in sorted((directory / image_type).glob("*.png"))
        ]
        if not files:
            print(f"FAIL {directory} no PNGs")
            passed = False
        for file, image_type in files:
            if not check(file, image_type):
                passed = False
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
