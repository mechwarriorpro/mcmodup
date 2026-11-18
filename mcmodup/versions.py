"""Conservative version matching for local dependency metadata."""

from dataclasses import dataclass
import re

_ORDERED = re.compile(r"([0-9]+(?:\.[0-9]+)*)(?:-(alpha|beta|rc)(?:[.-]?([0-9]+))?)?", re.I)
# Historical Applied Energistics releases use, for example, rv3-beta-5.
_REVISION = re.compile(r"rv([0-9]+)-(alpha|beta|rc|stable)(?:[.-]?([0-9]+))?", re.I)
_QUALIFIER = {"alpha": -3, "beta": -2, "rc": -1, "": 0, "stable": 0}
_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,127}")
Version = tuple[str, tuple[int, ...], int, int]


def declared_version(value: str) -> bool:
    return bool(_VERSION.fullmatch(value)) and value.casefold() not in {"unknown", "unspecified", "0.0.0-legacy"}


def _ordered(value: str) -> Version | None:
    base = value.partition("+")[0]
    if len(base) > 64:
        return None
    if match := _REVISION.fullmatch(base):
        return "rv", (int(match[1]),), _QUALIFIER[match[2].lower()], int(match[3] or "0")
    if match := _ORDERED.fullmatch(base):
        parts = [int(part) for part in match[1].split(".")]
        while parts and parts[-1] == 0:
            parts.pop()
        return "number", tuple(parts), _QUALIFIER[(match[2] or "").lower()], int(match[3] or "0")
    return None


@dataclass(frozen=True)
class Interval:
    lo: Version | None = None
    hi: Version | None = None
    include_lo: bool = False
    include_hi: bool = False

    @property
    def domain(self):
        bound = self.lo or self.hi
        return bound[0] if bound else None

    def intersection(self, other: "Interval") -> "Interval | None":
        if self.domain and other.domain and self.domain != other.domain:
            return None
        lo = max((v for v in (self.lo, other.lo) if v is not None), default=None)
        hi = min((v for v in (self.hi, other.hi) if v is not None), default=None)
        include_lo = all(r.lo != lo or r.include_lo for r in (self, other))
        include_hi = all(r.hi != hi or r.include_hi for r in (self, other))
        if lo is not None and hi is not None and (lo > hi or (lo == hi and not (include_lo and include_hi))):
            return None
        return Interval(lo, hi, include_lo, include_hi)


def _range(spec: str) -> str | Interval | None:
    spec = spec.strip()
    if spec in {"", "*"}:
        return Interval()
    if spec.startswith("[") and spec.endswith("]") and "," not in spec:
        spec = spec[1:-1].strip()
        if (exact := _ordered(spec)) is not None and "+" not in spec:
            return Interval(exact, exact, True, True)
    if declared_version(spec):
        return spec.casefold()
    if not (spec.startswith(("[", "(")) and spec.endswith(("]", ")")) and spec.count(",") == 1):
        return None
    lower, upper = (bound.strip() for bound in spec[1:-1].split(","))
    lo, hi = _ordered(lower), _ordered(upper)
    if (lower and lo is None) or (upper and hi is None) or not (lo or hi) or (lo and hi and lo[0] != hi[0]):
        return None
    return Interval(lo, hi, spec[0] == "[", spec[-1] == "]")


def supported_range(spec: str) -> bool:
    return _range(spec) is not None


def matches(version: str, spec: str) -> bool:
    """Check a literal or a single Maven-style interval; don't guess unsupported forms."""
    wanted = _range(spec)
    if wanted is None:
        return False
    if wanted == Interval():
        return True
    if not declared_version(version):
        return False
    if isinstance(wanted, str):
        return version.casefold() == wanted
    actual = _ordered(version)
    return actual is not None and wanted.intersection(Interval(actual, actual, True, True)) is not None


def ranges_disjoint(specs) -> bool:
    """Prove that supported positive constraints do not overlap; never infer from missing JARs."""
    specs = tuple(specs)
    parsed = [_range(spec) for spec in specs]
    if None in parsed:
        return False
    if literal := next((item for item in parsed if isinstance(item, str)), None):
        return not all(matches(literal, spec) for spec in specs)
    current = Interval()
    for bound in parsed:
        current = current.intersection(bound)
        if current is None:
            return True
    return False
