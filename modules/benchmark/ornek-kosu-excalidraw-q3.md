# Örnek koşu — excalidraw / q3

Tek bir soruyu iki kolda koşup cevapları yan yana koyan bir örnek. Amaç ContextGraph'ın
kazanımının nasıl göründüğünü somutlaştırmak; istatistiksel bir sonuç değil.

**Koşu:** `run-1787085544741.json` · profil `smoke` · 1 soru × 2 kol × 1 tekrar
**Ajan:** `gpt-4.1-mini` · **Hakem:** `gpt-4.1` · **Tool call tavanı:** 40

## Kurulum

| | ContextGraph'lı kol | Kontrol kolu |
|---|---|---|
| çalışma kopyası | `.benchmark-corpus/excalidraw/with` | `.benchmark-corpus/excalidraw/without` |
| indekslenmiş graf | var | **yok** |
| ajana verilen araçlar | `bash` + ContextGraph MCP araçları | sadece `bash` |

İki kol aynı repo'nun aynı commit'inden çıkmış iki ayrı çalışma kopyası. Kontrol kolunda
ContextGraph hiç kurulmamış: graf dosyası yok, MCP aracı ajana hiç sunulmuyor. Ajan
kullanmayı reddetmiyor — kullanabileceği bir araç hiç görmüyor.

## Soru

**Repo:** excalidraw · **Soru id:** `excalidraw-q3` · **Kategori:** `GRAPH_HEAVY`

> Trace how an element in the scene actually gets drawn onto the canvas: which React
> component invokes the static-scene render function, and where does the rendering code
> obtain (or generate) the roughjs shape it draws?

İki parçalı bir soru: (1) hangi React bileşeni render'ı başlatıyor, (2) roughjs şekli
nereden geliyor. Aşağıdaki fark neredeyse tamamen ikinci parçada.

## Gold key-facts

Her fact'in kanıtı kaynakta `dosya:satır` olarak sabitlenmiş. Hakem cevabı bu altı
maddeye karşı notlandırıyor, kendi bilgisine göre değil.

| # | fact | kanıt |
|---|---|---|
| f1 | `StaticCanvas`, effect'i her çalıştığında `renderStaticScene(...)` çağırır | `components/canvases/StaticCanvas.tsx:68` |
| f2 | `renderStaticScene`, `throttle=true` ile çağrılınca `renderStaticSceneThrottled`'a devrederek hemen döner | `renderer/staticScene.ts:475` |
| f3 | İç render rutini her eleman için `renderElement` çağırır | `renderer/staticScene.ts:314` |
| f4 | `renderElement`, roughjs Drawable'ı almak için `ShapeCache.generateElementShape(...)` çağırır | `renderer/renderElement.ts:811` |
| f5 | `generateElementShape`, WeakMap'te önbelleklenmiş şekil varsa onu yeniden üretmeden döndürür | `scene/ShapeCache.ts:65` |
| f6 | Önbellekte şekil yoksa `_generateElementShape(...)` çağrılarak yeni roughjs şekli üretilir | `scene/ShapeCache.ts:70` |

## Sonuç

| | ContextGraph'lı | Kontrol |
|---|---|---|
| **doğruluk** | **0.83** (6'da 5) | **0.50** (6'da 3) |
| tutturulan fact'ler | f1, f2, f3, f4, f6 | f1, f2, f3 |
| kaçırılanlar | f5 | **f4, f5, f6** |
| tool call | 4 | 40 (tavana çarptı) |
| dosya okuma | 0 | 10 |
| input token | 193.178 | 944.460 |
| süre | 20 sn | 270 sn |

Kaçırılan fact'ler rastgele dağılmıyor: kontrol kolunun kaçırdığı f4/f5/f6'nın üçü de
sorunun ikinci yarısı, yani `ShapeCache` zinciri. Kontrol kolu render akışının üst
katmanını buldu, roughjs şeklinin nereden geldiğini bulamadı.

## Cevaplar

### ContextGraph'lı kol — 4 tool call, 0 dosya okuma, 20 sn

Zinciri uçtan uca kuruyor ve her halkaya dosya yolu veriyor:

> 1. The React component named `StaticCanvas` (in
>    `packages/excalidraw/components/canvases/StaticCanvas.tsx`) is responsible for invoking
>    the `renderStaticScene` function. It does so inside a `useEffect` hook […]
>
> 4. For generating roughjs shapes, `renderElement` calls `ShapeCache.generateElementShape`
>    (from `packages/excalidraw/scene/ShapeCache.ts`). This function either fetches the
>    cached shape or generates a new shape using `_generateElementShape`.
>
> 5. The `_generateElementShape` function (in `packages/excalidraw/scene/Shape.ts`) uses a
>    roughjs generator to create the shape representation based on the element type.

Dikkat çeken nokta: bu kol **hiç dosya okumadı**. `ShapeCache.ts` ve `Shape.ts`
isimlerini, aralarındaki çağrı ilişkisini ve önbellek davranışını yalnızca graf
çıktısından üretti.

### Kontrol kolu — 40 tool call, 10 dosya okuma, 270 sn

İlk üç adımı doğru kuruyor, sonra bütçesi bitiyor ve ne bulamadığını kendisi yazıyor:

> For each visible element, `_renderStaticScene` calls the `renderElement` function which is
> responsible for rendering an individual element. […] The specifics of shape generation and
> the interaction with Rough.js are handled in the `renderElement` function […]
>
> **I could not confirm from the source directly the exact way the roughjs shape gets
> generated inside `renderElement`**, but it is clear from the call chain and parameter
> passing that the RoughCanvas provided to `StaticCanvas` flows through […]

Bu cümle ölçümün en okunaklı parçası: kaçırdığı üç fact'in tam olarak neresi olduğunu
ajanın kendisi işaret ediyor. 40 tool call ve 10 dosya okumadan sonra `ShapeCache`
katmanına ulaşamamış.

## Bu sayıları nasıl okumalı

**Verimlilik farkı temiz.** 4 çağrıya karşı 40, 193k token'a karşı 944k, 20 saniyeye karşı
270 saniye. Kontrol kolunun tavana çarpması burada bir sakatlık değil, bulgunun kendisi:
bütçesini tamamen harcayıp yine de eksik cevap verdi.

**Doğruluk farkı daha zayıf bir iddia.** 0.50, tavana çarpmış bir kolun puanı, yani bir alt
sınır; bütçesi bitmeseydi daha yükseğe çıkabilirdi. "ContextGraph %66 daha doğru" demek bu
veriden çıkmaz.

**n = 1.** Tek soru, tek tekrar, varyans yok. `full` profili aynı soruyu 4 kez koşup medyan
alır; genellenebilir bir sayı oradan çıkar, buradan değil.

**Erken bir ölçüm bu farkı iki katı gösteriyordu.** Düzeltilmeden önce kontrol kolu tavana
çarpınca hiç cevap vermiyordu ve aynı soruda 0.83'e karşı **0.00** okunuyordu. Farkın
yaklaşık yarısı ContextGraph'ın katkısı değil, kontrol kolunun susturulmasının eseriymiş.
Tavan artık koşuyu değil yalnızca araç fazını bitiriyor; ajan bütçesi bitince topladığı
kanıtla cevap veriyor. Yukarıdaki 0.50 o düzeltmeden sonraki gerçek değer.
