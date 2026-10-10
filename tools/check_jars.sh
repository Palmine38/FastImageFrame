#!/bin/bash
cd "$(dirname "$0")/.."
status=0
names="shouldRender|renderEntities|submitEntities|getFramedMapId|extractRenderState|getPackedLightCoords|renderSkull|submitSkull|getRenderType|getPlayerSkinRenderType|setSectionDirty|renderLevel|renderBlockEntities|submitBlockEntities|extractArgument|tryExtractRenderState|setOwner|loadAdditional|fixedBuffers|byPath|destructionProgress|vertexPointer|elementsToFill|endLastVertex|getOrDefault|submitModel|entityTranslucent"
for jar in dist/*.jar; do
    case "$jar" in *-26.*) continue ;; esac
    tmp=$(mktemp -d)
    unzip -q -o "$jar" -d "$tmp"
    classes=("$tmp"/fastimageframes/mixin/*.class)
    constants=$(for c in "${classes[@]}"; do javap -v -p -constants "$c"; done 2>/dev/null \
        | grep -E "ConstantValue: String " | sed 's/.*ConstantValue: String //' | sort -u)
    left=$(for c in "${classes[@]}"; do javap -v "$c"; done 2>/dev/null \
        | grep -E "= Utf8 " | sed 's/.*= Utf8 *//' | grep -vxF -f <(printf '%s\n' "$constants") \
        | grep -E "net/minecraft/(client|world|server|core|resources|util)/|^($names)(\(|$)|;($names)(\(|$)" | sort -u)
    rm -rf "$tmp"
    if [ -n "$left" ]; then
        echo "$(basename "$jar"): not remapped:"
        echo "$left" | sed 's/^/   /'
        status=1
    else
        echo "$(basename "$jar"): ok"
    fi
done
exit $status
