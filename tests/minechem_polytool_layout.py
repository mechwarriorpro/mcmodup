"""Read the original Polytool's static client constants without loading any client classes."""

import re
import subprocess
from pathlib import Path
from zipfile import ZipFile


def layout(javap: Path, jar: Path) -> dict[str, str]:
    def bytecode(owner: str) -> str:
        return subprocess.run([str(javap), "-c", "-p", "-classpath", str(jar), "minechem.item." + owner], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20).stdout

    helper = bytecode("element.ElementGuiHelper")
    screen = bytecode("polytool.PolytoolGui")
    radius = re.findall(r"(?m)^\s*\d+: bipush\s+(\d+)\s*\n\s*\d+: imul\b", helper)
    dimensions = re.findall(r"(?m)^\s*\d+: sipush\s+(\d+)\s*\n\s*\d+: putfield\b", screen)
    angles = re.findall(r"// double ([\d.]+)d\b", helper)
    assert len(radius) == 1 and len(dimensions) == 2 and len(angles) == 3, (radius, dimensions, angles)
    assert helper.count("java/lang/Math.sin:") == helper.count("java/lang/Math.cos:") == 1, "Orbit drawing no longer calls sin and cos once per frame"
    assert "java/util/Random.nextInt:(I)I" in screen and re.search(
        r"(?m)^\s*\d+: iconst_2\s*\n\s*\d+: invokevirtual[^\n]*Random.nextInt:\(I\)I\s*\n\s*\d+: iadd", screen), "Orbit ring selection no longer adds 1 to the two-way roll"
    assert "ElementGuiHelper.\"<init>\"" in screen and "PolytoolUpgradeType.power:F" in screen, "Orbit no longer uses the Polytool rank"
    help_text = "-"
    if "gui/GuiTabHelp" in screen:
        with ZipFile(jar) as source:
            (language,) = (name for name in source.namelist() if name.lower() == "assets/minechem/lang/en_us.lang")
            (help_text,) = (line.split("=", 1)[1].strip() for line in source.read(language).decode("utf-8-sig").splitlines()
                            if line.startswith("help.polytool="))
    return {"sheet": "x".join(dimensions), "radius": str(radius[0]) + "/" + str(2 * int(radius[0])),
            "step": angles[0], "center": "/".join(str(int(float(angle))) for angle in angles[1:]), "help": help_text}
