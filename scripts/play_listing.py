"""Push the store listing under play/listing/<locale>/ to Play, or print what Play has.

Runs in the "Play store listing" workflow with the same service account the
internal-testing pipeline uses. Credentials come from Application Default
Credentials (in CI, the file the auth step writes). Layout follows fastlane's
supply convention so the files are readable by other tools too:

    play/listing/en-US/title.txt
    play/listing/en-US/short_description.txt
    play/listing/en-US/full_description.txt
    play/listing/en-US/images/icon.png
    play/listing/en-US/images/featureGraphic.png

Screenshots are rendered in CI and no longer committed, so upload carries
text, icon and feature graphic only. The screenshots mode replaces phone
and Wear screenshots from the supplied directory without changing other assets.
The icon and feature graphic are rendered from the app by ListingImagesTest and
CI fails when the committed copies differ; download the listing-images artifact
to update them.

Usage: play_listing.py inspect|upload|screenshots [--images <dir>]
"""
import argparse
import json
from pathlib import Path

import google.auth
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "cloud.trotter.log.strength"
LISTING_DIR = Path("play/listing")
LOCALES = ("en-US",)  # only what the listing actually maintains; a stray folder must not publish
IMAGE_TYPES = {
    "icon.png": "icon",
    "featureGraphic.png": "featureGraphic",
    "phoneScreenshots": "phoneScreenshots",
    "wearScreenshots": "wearScreenshots",
}


def service():
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    # Live discovery: the client's bundled document lags the API and lacks
    # commit's changesInReviewBehavior, which the upload relies on.
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False, static_discovery=False)


def inspect(api, edit_id):
    edits = api.edits()
    print("details:", json.dumps(edits.details().get(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    print("listings:", json.dumps(edits.listings().list(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    for image_type in sorted(set(IMAGE_TYPES.values())):
        images = edits.images().list(
            packageName=PACKAGE, editId=edit_id, language="en-US", imageType=image_type,
        ).execute()
        print(f"images/{image_type}:", [i["id"] for i in images.get("images", [])])
    print("tracks:", json.dumps(edits.tracks().list(packageName=PACKAGE, editId=edit_id).execute(), indent=2))


def upload(api, edit_id):
    edits = api.edits()
    for locale in LOCALES:
        locale_dir = LISTING_DIR / locale
        body = {
            "language": locale,
            "title": (locale_dir / "title.txt").read_text().strip(),
            "shortDescription": (locale_dir / "short_description.txt").read_text().strip(),
            "fullDescription": (locale_dir / "full_description.txt").read_text().strip(),
        }
        edits.listings().update(packageName=PACKAGE, editId=edit_id, language=locale, body=body).execute()
        print(f"{locale}: listing text updated")
        images_dir = locale_dir / "images"
        for name, image_type in IMAGE_TYPES.items():
            source = images_dir / name
            files = sorted(source.glob("*.png")) if source.is_dir() else ([source] if source.exists() else [])
            upload_images(edits, edit_id, locale, image_type, files)
    # Never cancel a release that is sitting in review to get a listing out.
    edits.commit(packageName=PACKAGE, editId=edit_id, changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
    print("committed")


def upload_images(edits, edit_id, locale, image_type, files):
    if not files:
        return
    edits.images().deleteall(
        packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
    ).execute()
    for file in files:
        edits.images().upload(
            packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
            media_body=MediaFileUpload(str(file), mimetype="image/png"),
        ).execute()
        print(f"{locale}: {image_type} <- {file.name}")


def screenshots(api, edit_id, images):
    edits = api.edits()
    for locale in LOCALES:
        for image_type, files in images.items():
            upload_images(edits, edit_id, locale, image_type, files)
    edits.commit(packageName=PACKAGE, editId=edit_id, changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
    print("committed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", nargs="?", default="inspect", choices=("inspect", "upload", "screenshots"))
    parser.add_argument("--images", type=Path)
    args = parser.parse_args()
    if args.mode == "screenshots":
        if args.images is None:
            raise SystemExit("screenshots requires --images <dir>")
        images = {
            image_type: sorted((args.images / image_type).glob("*.png"))
            for image_type in ("phoneScreenshots", "wearScreenshots")
        }
        if not any(images.values()):
            raise SystemExit("no PNGs found in phoneScreenshots or wearScreenshots")
    api = service()
    edit_id = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    if args.mode == "upload":
        upload(api, edit_id)
    elif args.mode == "screenshots":
        screenshots(api, edit_id, images)
    else:
        try:
            inspect(api, edit_id)
        finally:
            api.edits().delete(packageName=PACKAGE, editId=edit_id).execute()


if __name__ == "__main__":
    main()
