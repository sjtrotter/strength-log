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
    play/listing/en-US/images/phoneScreenshots/*.png
    play/listing/en-US/images/wearScreenshots/*.png

Usage: play_listing.py inspect|upload
"""
import json
import sys
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
            if not files:
                continue
            edits.images().deleteall(
                packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
            ).execute()
            for file in files:
                edits.images().upload(
                    packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
                    media_body=MediaFileUpload(str(file), mimetype="image/png"),
                ).execute()
                print(f"{locale}: {image_type} <- {file.name}")
    # Never cancel a release that is sitting in review to get a listing out.
    edits.commit(packageName=PACKAGE, editId=edit_id, changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
    print("committed")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "inspect"
    api = service()
    edit_id = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    if mode == "upload":
        upload(api, edit_id)
    else:
        try:
            inspect(api, edit_id)
        finally:
            api.edits().delete(packageName=PACKAGE, editId=edit_id).execute()


if __name__ == "__main__":
    main()
