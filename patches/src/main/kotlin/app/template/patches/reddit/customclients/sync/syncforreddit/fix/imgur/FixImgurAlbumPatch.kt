package app.template.patches.reddit.customclients.sync.syncforreddit.fix.imgur

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.template.patches.reddit.customclients.sync.syncforreddit.SyncForRedditCompatible

private fun getUrlExtractionSmali(baseUrl: String): String = """
    const-string v0, "?"
    invoke-virtual {p0, v0}, Ljava/lang/String;->indexOf(Ljava/lang/String;)I
    move-result v0
    const/4 v1, -0x1
    if-eq v0, v1, :no_query
    const/4 v2, 0x0
    invoke-virtual {p0, v2, v0}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object p0
    :no_query
    
    const-string v0, "#"
    invoke-virtual {p0, v0}, Ljava/lang/String;->indexOf(Ljava/lang/String;)I
    move-result v0
    if-eq v0, v1, :no_hash
    const/4 v2, 0x0
    invoke-virtual {p0, v2, v0}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object p0
    :no_hash
    
    const-string v0, "/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no_trailing_slash
    const/4 v0, 0x0
    invoke-virtual {p0}, Ljava/lang/String;->length()I
    move-result v2
    add-int/lit8 v2, v2, -0x1
    invoke-virtual {p0, v0, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object p0
    :no_trailing_slash
    
    const-string v0, "/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v0
    if-eq v0, v1, :no_slash
    add-int/lit8 v0, v0, 0x1
    invoke-virtual {p0, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object p0
    :no_slash
    
    const-string v0, "-"
    invoke-virtual {p0, v0}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v0
    if-eq v0, v1, :no_hyphen
    add-int/lit8 v0, v0, 0x1
    invoke-virtual {p0, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object p0
    :no_hyphen
    
    const-string v0, "."
    invoke-virtual {p0, v0}, Ljava/lang/String;->lastIndexOf(Ljava/lang/String;)I
    move-result v0
    if-eq v0, v1, :no_dot
    const/4 v2, 0x0
    invoke-virtual {p0, v2, v0}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object p0
    :no_dot
    
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "$baseUrl"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object p0
    
    return-object p0
""".trimIndent()

val fixImgurAlbumPatch = bytecodePatch(
    name = "Fix Imgur Albums",
    description = "Restores native Imgur Album/Gallery viewing by bypassing the dead syncforreddit proxy and communicating directly with the official Imgur API.",
    default = true
) {
    compatibleWith(*SyncForRedditCompatible)

    val clientIdOption = stringOption(
        "imgur-client-id",
        "546c25a59c58ad7",
        null,
        "Imgur Client ID",
        "Your Imgur API Client ID. This is required to fix album loading. The default value is the official Imgur App Client ID, which bypasses registration.",
        true,
        validator = { value ->
            if (value.isNullOrBlank()) {
                return@stringOption false
            }
            true
        }
    )

    execute {
        val clientId = clientIdOption.value!!
        
        imgurGalleryUrlStaticFingerprint.method.apply {
            addInstructions(0, getUrlExtractionSmali("https://api.imgur.com/3/album/"))
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

        imgurImageUrlStaticFingerprint.method.apply {
            addInstructions(0, getUrlExtractionSmali("https://api.imgur.com/3/image/"))
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
