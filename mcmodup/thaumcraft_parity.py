"""Apply measured TC4 behavior fixes to the pinned standalone Reload source."""

from collections import Counter
from pathlib import Path
from zipfile import ZipFile

from .inputs import InputError


SOURCE_ROOT = "Thaumcraft-1.21.1-1417e30fa04ce349a8d811d8f1b3d7515cc58643"
SOURCE_SHA256 = "729c1ed1fcb6d22f04744c9cf68105b279699128e1f4bb587027944a970173bf"
ORIGINAL_SHA256 = "3dba9786966974701578a658d1bb369bf35bdf5f363079f5ac9c4910a39113be"
BASE_VERSION = "0.1.0-alpha.24"
VERSION = BASE_VERSION + "-mcmodup.1"


def _edit(text: str, original: str, replacement: str) -> str:
    if text.count(original) != 1:
        raise InputError(f"Expected one Thaumcraft parity patch anchor: {original.strip().splitlines()[0]!r}")
    return text.replace(original, replacement, 1)


def _aspects(text: str) -> str:
    return _edit(text, "            if (result == null) result = fromCrafting(candidates);\n",
                 """            if (result == null) result = fromCrafting(candidates);
            if (result == null && item == Items.ENCHANTED_GOLDEN_APPLE) {
                // Forge 1.7.10 crafted metadata 1 from eight gold blocks and an apple.
                // Preserve the observed recipe-derived base, not the complex binding's
                // separate MAGIC/HEAL additions. Do not add a new vanilla recipe or
                // override an explicit tag or a recipe supplied by this pack.
                result = new AspectList().add(Aspect.GREED, 64).add(Aspect.CROP, 1).add(Aspect.METAL, 64);
            }
""")


def _node(text: str) -> str:
    text = _edit(text, "import java.util.UUID;\n", "")
    text = _edit(text, "import thaumcraft.common.lib.world.NodeStabilizerRules;\n", """import thaumcraft.common.lib.world.NodeStabilizerRules;
import thaumcraft.common.lib.world.OuterLandsProvider;
import thaumcraft.common.lib.world.OuterLandsRules;
""")
    text = _edit(text, "private String scanId = UUID.randomUUID().toString();", "private String scanId;")
    start = text.index("    /**\n     * Original {@code TileNode:98-101}: the identity of the node.")
    stop = text.index("    @Override public NodeType getNodeType()", start)
    text = _edit(text, text[start:stop], """    @Override public String getId() { return getScanId(); }

    /** TC4 generates the identity once from its initial dimension and position. */
    public String getScanId() {
        if (scanId != null) return scanId;
        String coords = ":" + worldPosition.getX() + ":" + worldPosition.getY() + ":" + worldPosition.getZ();
        // Detached block entities do not have a dimension yet. Don't persist a
        // guess; wait for attachment or an identity restored from a jar/save.
        if (level == null) return "0" + coords;
        var key = level.dimension();
        String dimension;
        if (key.equals(Level.OVERWORLD)) dimension = "0";
        else if (key.equals(Level.NETHER)) dimension = "-1";
        else if (key.equals(Level.END)) dimension = "1";
        else if (key.equals(OuterLandsProvider.DIMENSION)) dimension = Integer.toString(OuterLandsRules.DIMENSION_ID);
        // Other namespaced dimensions have no agreed historical numeric ID.
        else dimension = key.location().toString();
        scanId = dimension + coords;
        return scanId;
    }
""")
    text = _edit(text, "     * drains every current key toZERO; UUID is freshly generated, not preserved. */",
                 "     * drains every current key toZERO; the identity is generated for the replacement. */")
    text = _edit(text, '        if (!savedId.isEmpty()) scanId = savedId;',
                 '        scanId = savedId.isEmpty() ? null : savedId;')
    return _edit(text, '        tag.putString("scanId", scanId);',
                 '        if (scanId != null || level != null) tag.putString("scanId", getScanId());')


def prepare_source(source: Path, output: Path) -> None:
    """Rewrite three pinned source files without importing code or assets from the original JAR."""
    prefix = f"{SOURCE_ROOT}/common/src/main/java/thaumcraft/common/"
    transforms = {
        prefix + "lib/research/ObjectAspectResolver.java": _aspects,
        prefix + "tiles/TileNode.java": _node,
        f"{SOURCE_ROOT}/gradle.properties": lambda text: _edit(text, f"mod_version={BASE_VERSION}\n", f"mod_version={VERSION}\n"),
    }
    with ZipFile(source) as upstream:
        counts = Counter(info.filename for info in upstream.infolist())
        for name in transforms:
            if counts[name] != 1:
                raise InputError(f"Expected exactly one file to patch in the pinned Thaumcraft source: {name}")
        patched = {name: transform(upstream.read(name).decode("utf-8")) for name, transform in transforms.items()}
        with ZipFile(output, "x") as rewritten:
            for info in upstream.infolist():
                if info.filename in patched:
                    rewritten.writestr(info, patched[info.filename].encode("utf-8"))
                else:
                    rewritten.writestr(info, upstream.read(info))
