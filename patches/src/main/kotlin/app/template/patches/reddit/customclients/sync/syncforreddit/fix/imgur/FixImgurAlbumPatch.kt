package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imgur

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

private fun getUrlExtractionSmali(fieldClass: String, fieldName: String, baseUrl: String): String = """
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "$baseUrl"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    iget-object v1, p0, ${fieldClass}->${fieldName}:Ljava/lang/String;
    
    const-string v2, "-"
    invoke-virtual {v1, v2}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v2
    if-ltz v2, :no_hyphen
    add-int/lit8 v2, v2, 0x1
    invoke-virtual {v1, v2}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object v1
    :no_hyphen
    
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
""".trimIndent()

val fixImgurAlbumPatch = bytecodePatch(
    name = "Fix imgur links",
    description = "Fixes imgur.com/a/ links to properly display in the image viewer.",
    default = true
) {
    compatibleWith(*SyncForRedditCompatible)

    execute {
        val clientId = "546c25a59c58ad7" // official Imgur App Client ID
        
        externalGalleryUrlFingerprint.method.apply {
            addInstructions(0, getUrlExtractionSmali("Lg8/b;", "a", "https://api.imgur.com/3/album/"))
        }

        imgurGalleryRequestHeadersFingerprint.method.apply {
            addInstructions(0, """
                new-instance v0, Ljava/util/HashMap;
                invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
                
                const-string v1, "User-Agent"
                const-string v2, "sync_for_reddit"
                invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                
                const-string v1, "Authorization"
                const-string v2, "Client-ID $clientId"
                invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                
                return-object v0
            """)
        }

        externalImageUrlFingerprint.method.apply {
            addInstructions(0, getUrlExtractionSmali("Lg8/d;", "a", "https://api.imgur.com/3/image/"))
        }

        imgurImageRequestHeadersFingerprint.method.apply {
            addInstructions(0, """
                new-instance v0, Ljava/util/HashMap;
                invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
                
                const-string v1, "User-Agent"
                const-string v2, "sync_for_reddit"
                invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                
                const-string v1, "Authorization"
                const-string v2, "Client-ID $clientId"
                invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                
                return-object v0
            """)
        }
    }
}
