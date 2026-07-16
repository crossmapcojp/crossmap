# Google Maps page cache

`CachedGoogleMapsPageSource.load` reads and writes one Google Maps HTML response per CID under `pages/`.
`GoogleMapsPlaceResolver` consumes the pages through `GoogleMapsPlaceParser`. Keeping these pages avoids sending
the same Google request again; the HTML payload is not committed.
