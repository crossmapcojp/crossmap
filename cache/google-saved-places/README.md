# Google Saved Places cache

`ReadGoogleSavedPlaces` and `GoogleSavedPlacesSeedReader.readDirectory` write normalized Takeout seeds and reports
here. `GoogleMapsPlaceResolver.resolve` reads those seeds and writes resolved `google-place-candidates.json` for
`GoogleSavedPlacesCleanupWorkflow.preparePendingCatalog`. The original personal Takeout CSV copies and exclusions
are machine-local inputs and are not committed.
