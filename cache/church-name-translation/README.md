# Church name translation cache

`CachingChurchNameComponentTranslator.translateAll` uses `components.json` to reuse typed Japanese name-part
translations. `CachingChurchEnglishNameTranslator.translateAll` uses `whole-names.json` as a resumable checkpoint
for fully composed and validated names. Cache model/version fields invalidate incompatible or low-quality runs.
Neither cache is canonical data; publication writes validated names to `resources/catalog/churches.json` atomically.
