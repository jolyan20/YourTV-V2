#!/usr/bin/env python3
"""The games of the leagues the screen shows, from yesterday to a month ahead, one small JSON per league, for the
screen to read when ESPN and TheSportsDB do not answer it (and at once on start, before they do). Run in CI
(.github/workflows/fixtures.yml) every few hours; both APIs are unreachable from the dev container, so the report
(fixtures/report.json) says what each answered in the real run.

ESPN's scoreboard gives the whole window in one or a few calls; TheSportsDB's eventsday.php gives the coming week
with its own team ids (the ids the screen follows teams by). A game both list is kept once, TheSportsDB's copy with
ESPN's crests if it has none; an ESPN-only game takes TheSportsDB's ids for its teams where the league's other games
name them. A league where both failed keeps its last file, marked "stale".

Out: fixtures/<TheSportsDB league id>.json = {"id", "league", "updated", "stale", "events": [...]}, events in
TheSportsDB's shape (the screen's espnEvent() makes the same shape), and fixtures/report.json.
"""
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SDB = "https://www.thesportsdb.com/api/v1/json/123/"
ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer/{}/scoreboard"
# the screen's TOP_LEAGUES, with ESPN's names for each (the first that answers is kept and reported)
LEAGUES = [
    ("4668", "دوري روشن", ["ksa.1"]),
    ("5649", "كأس الملك", ["ksa.kings.cup", "ksa.kings_cup"]),
    ("5650", "كأس السوبر السعودي", ["ksa.super_cup", "ksa.super.cup"]),
    ("4719", "أبطال آسيا للنخبة", ["afc.champions", "afc.cl", "afc.champions_elite"]),
    ("4480", "دوري أبطال أوروبا", ["uefa.champions"]),
    ("4328", "الدوري الإنجليزي", ["eng.1"]),
    ("4335", "الدوري الإسباني", ["esp.1"]),
    ("4332", "الدوري الإيطالي", ["ita.1"]),
]
BACK_DAYS, AHEAD_DAYS, SDB_DAYS = 1, 30, 2
# a hard limit for the whole run (the job stops at 40 minutes): club searches end first, then TheSportsDB is skipped
START = time.time()
SEARCH_UNTIL, SDB_UNTIL = START + 12 * 60, START + 25 * 60
OUT = Path(os.environ.get("FIXTURES_OUT", "fixtures"))
PREV = Path(os.environ.get("FIXTURES_PREV", "prev/fixtures"))
KEEP = ("idEvent", "idLeague", "strLeague", "strLeagueBadge", "strTimestamp", "dateEvent", "strTime", "strHomeTeam",
        "strAwayTeam", "idHomeTeam", "idAwayTeam", "strHomeTeamBadge", "strAwayTeamBadge", "intHomeScore",
        "intAwayScore", "strStatus", "strProgress", "intRound", "strSeason", "strVenue")


def get(url, pace=0.0, tries=3):
    """JSON or None; TheSportsDB's free key is paced, waits once on 429 and gives up after its own deadline."""
    for attempt in range(tries):
        if pace and time.time() > SDB_UNTIL:
            return None
        if pace:
            time.sleep(pace)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "yourtv-v2-fixtures", "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                if pace and attempt:
                    return None
                time.sleep(30)
                continue
            if e.code in (400, 404):
                return None
        except Exception:
            time.sleep(3)
    return None


def key(name):
    s = re.sub(r"\b(fc|sc|cf|afc|club|saudi club)\b", "", str(name).lower())
    return re.sub(r"^al ", "al", re.sub(r"[^a-z0-9]+", " ", s).strip())


def words(name):
    return [w for w in key(name).split() if len(w) > 2 and w not in ("al", "fc", "sc", "sfc", "club", "cf", "de", "the", "united", "city")]


def name_like(a, b):
    return key(a) == key(b) or bool(set(words(a)) & set(words(b)))


def same_teams(a, b):
    """both teams alike, either way round (one alone is not enough: Real Madrid and Real Sociedad kick off together)"""
    h, w, x, y = a.get("strHomeTeam", ""), a.get("strAwayTeam", ""), b.get("strHomeTeam", ""), b.get("strAwayTeam", "")
    return (name_like(h, x) and name_like(w, y)) or (name_like(h, y) and name_like(w, x))


def when(ev):
    s = ev.get("strTimestamp") or "{}T{}".format(ev.get("dateEvent"), ev.get("strTime") or "00:00:00")
    s = s.replace("Z", "").split("+")[0]
    try:
        return datetime.datetime.fromisoformat(s).replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        return None


def espn_event(e, league, lg_logo):
    c = (e.get("competitions") or [{}])[0]
    st = e.get("status") or c.get("status") or {}
    ty = st.get("type") or {}
    side = {x.get("homeAway"): x for x in c.get("competitors") or []}
    home, away = side.get("home") or {}, side.get("away") or {}
    pre, state, name = ty.get("state") == "pre", ty.get("state"), str(ty.get("name") or "")
    if "POSTPONED" in name: status = "Postponed"
    elif "CANCEL" in name: status = "Cancelled"
    elif "ABANDON" in name: status = "Abandoned"
    elif "SUSPEND" in name or "DELAY" in name: status = "Suspended"
    elif "HALFTIME" in name: status = "HT"
    elif state == "post": status = "PEN" if "PEN" in name else "AET" if ("AET" in name or "EXTRA" in name) else "FT"
    elif state == "in": status = "LIVE"
    else: status = "NS"

    def score(x):
        v = x.get("score")
        if pre or v in (None, ""):
            return None
        v = v.get("value") if isinstance(v, dict) else v
        return re.sub(r"\.0$", "", str(v))

    def logo(x):
        t = x.get("team") or {}
        return t.get("logo") or ((t.get("logos") or [{}])[0]).get("href") or ""

    t = when({"strTimestamp": str(e.get("date") or "").replace("Z", "")})
    return {"idEvent": "espn" + str(e.get("id")), "idLeague": league, "strLeagueBadge": lg_logo,
            "strTimestamp": t.strftime("%Y-%m-%dT%H:%M:%S") if t else "",
            "strHomeTeam": (home.get("team") or {}).get("displayName") or "", "strAwayTeam": (away.get("team") or {}).get("displayName") or "",
            "idHomeTeam": "espn" + str((home.get("team") or {}).get("id")), "idAwayTeam": "espn" + str((away.get("team") or {}).get("id")),
            "strHomeTeamBadge": logo(home), "strAwayTeamBadge": logo(away), "intHomeScore": score(home), "intAwayScore": score(away),
            "strStatus": status, "strProgress": re.sub(r"[^0-9+]", "", str(st.get("displayClock") or "")) if state == "in" else ""}


def espn_league(league, slugs, start, end):
    """(slug that answered, events) or (None, None)"""
    for slug in slugs:
        got, ok = {}, False
        j = get(ESPN.format(slug) + "?limit=500&dates={}-{}".format(start.strftime("%Y%m%d"), end.strftime("%Y%m%d")))
        days = [] if j and j.get("events") else [start + datetime.timedelta(days=i) for i in range((end - start).days + 1)]
        pages = [j] if j and j.get("events") else []
        for d in days:   # no range answer: a day at a time
            pages.append(get(ESPN.format(slug) + "?limit=200&dates=" + d.strftime("%Y%m%d")))
        for p in pages:
            if not p or not isinstance(p.get("events"), list):
                continue
            ok = True
            lg = (p.get("leagues") or [{}])[0]
            logo = ((lg.get("logos") or [{}])[0]).get("href") or ""
            for e in p["events"]:
                ev = espn_event(e, league, logo)
                if ev["strHomeTeam"] and ev["strAwayTeam"]:
                    got[ev["idEvent"]] = ev
        if ok:
            return slug, list(got.values())
    return None, None


def sdb_league(league, start):
    """TheSportsDB's games of the coming week, with its own ids; None when it never answered"""
    got, ok = {}, False
    paths = ["eventsday.php?d={}&l={}".format((start + datetime.timedelta(days=i)).strftime("%Y-%m-%d"), league) for i in range(SDB_DAYS)]
    paths += ["eventsnextleague.php?id=" + league, "eventspastleague.php?id=" + league]
    for p in paths:
        j = get(SDB + p, pace=2.5)
        if j is None:
            continue
        ok = True
        for ev in (j.get("events") or []):
            if ev and ev.get("idEvent"):
                got[str(ev["idEvent"])] = {k: ev.get(k) for k in KEEP if ev.get(k) not in (None, "")}
    return list(got.values()) if ok else None


# a cup's clubs are looked up as its country's league's (the same names)
CLUB_SCOPE = {"5649": "4668", "5650": "4668"}
SEARCH_BUDGET, RETRY_DAYS = 60, 7


def search_club(name, league):
    """TheSportsDB's id of an ESPN club name: one that plays in the league if the search finds several;
    "" when none fits, None when TheSportsDB did not answer"""
    answered = False
    for q in dict.fromkeys([name, re.sub(r"^Al ", "Al-", name)]):
        j = get(SDB + "searchteams.php?t=" + urllib.parse.quote(q), pace=2.5)
        if j is None:
            continue
        answered = True
        teams = [t for t in (j.get("teams") or []) if t and t.get("idTeam") and (t.get("strSport") or "Soccer") == "Soccer"]
        named = [t for t in teams if any(name_like(n, name) and (set(words(n)) <= set(words(name)) or set(words(name)) <= set(words(n)))
                                         for n in [t.get("strTeam") or ""] + (t.get("strTeamAlternate") or "").split(",") if n.strip())]
        here = [t for t in named if league in {str(t.get(k)) for k in ("idLeague", "idLeague2", "idLeague3", "idLeague4",
                                                                      "idLeague5", "idLeague6", "idLeague7") if t.get(k)}]
        pick = here[0] if len(here) == 1 else named[0] if len(named) == 1 else None
        if pick:
            return str(pick["idTeam"])
    return "" if answered else None


class Clubs:
    """{scope|name: [id or "", day searched]}, kept between runs in fixtures/clubs.json"""
    def __init__(self, prev):
        try:
            self.map = json.loads(prev.read_text(encoding="utf-8"))
        except Exception:
            self.map = {}
        self.budget, self.searched = SEARCH_BUDGET, 0

    def id(self, league, name):
        k = CLUB_SCOPE.get(league, league) + "|" + key(name)
        v = self.map.get(k)
        today = datetime.date.today()
        if v and (v[0] or (today - datetime.date.fromisoformat(v[1])).days < RETRY_DAYS):
            return v[0] or None
        if self.budget <= 0 or time.time() > SEARCH_UNTIL:
            return None
        self.budget -= 1
        found = search_club(name, CLUB_SCOPE.get(league, league))
        self.searched += 1
        if found is not None:
            self.map[k] = [found, today.isoformat()]
            self.save()   # after each: a run cut short keeps what it found
        print("club", league, name, "->", found, flush=True)
        return found or None

    def save(self):
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "clubs.json").write_text(json.dumps(self.map, ensure_ascii=False, indent=0, sort_keys=True), encoding="utf-8")


def merge(sdb, espn, clubs=None, league=None):
    """clubs: ESPN's club names looked up on TheSportsDB (Clubs), for the ESPN games TheSportsDB did not list"""
    out = list(sdb or [])
    ids = {}   # ESPN's team name -> TheSportsDB's id
    for ev in out:
        for side in ("Home", "Away"):
            if ev.get("id%sTeam" % side):
                ids[key(ev.get("str%sTeam" % side, ""))] = ev.get("id%sTeam" % side)
    for e in espn or []:
        te = when(e)
        twin = next((s for s in out if te and when(s) and abs((when(s) - te).total_seconds()) < 1200 and same_teams(s, e)), None)
        if twin:   # one game: TheSportsDB's ids, ESPN's crests and score where TheSportsDB has none
            for k in ("strHomeTeamBadge", "strAwayTeamBadge", "strLeagueBadge", "intHomeScore", "intAwayScore"):
                if not twin.get(k) and e.get(k):
                    twin[k] = e[k]
            if e.get("strStatus") not in ("NS", "LIVE") and twin.get("strStatus") in (None, "", "NS", "Not Started"):
                twin["strStatus"] = e["strStatus"]
            continue
        for side in ("Home", "Away"):
            n = e["str%sTeam" % side]
            # the same name, or one name's words all in the other's, and only one such team in the league
            near = {v for k, v in ids.items() if v and words(k) and words(n) and (set(words(k)) <= set(words(n)) or set(words(n)) <= set(words(k)))}
            sid = ids.get(key(n)) or (near.pop() if len(near) == 1 else None) or (clubs.id(league, n) if clubs else None)
            if sid:
                e["id%sTeam" % side] = sid
        out.append(e)
    return sorted(out, key=lambda ev: ev.get("strTimestamp") or "")


def main():
    only = set(sys.argv[1:])
    OUT.mkdir(parents=True, exist_ok=True)
    today = datetime.datetime.now(datetime.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    start, end = today - datetime.timedelta(days=BACK_DAYS), today + datetime.timedelta(days=AHEAD_DAYS)
    report = {"updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "leagues": []}
    clubs = Clubs(PREV / "clubs.json")
    for lid, name, slugs in LEAGUES:
        if only and lid not in only:
            continue
        slug, espn = espn_league(lid, slugs, start, end)
        sdb = sdb_league(lid, start)
        row = {"id": lid, "league": name, "espn": slug, "espnGames": None if espn is None else len(espn),
               "sdbGames": None if sdb is None else len(sdb)}
        path = OUT / (lid + ".json")
        if espn is None and sdb is None:
            prev = PREV / (lid + ".json")
            if prev.exists():
                data = json.loads(prev.read_text(encoding="utf-8"))
                data["stale"] = True
                path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            row["kept"] = prev.exists()
        else:
            events = [ev for ev in merge(sdb, espn, clubs, lid) if (lambda t: t is None or start <= t <= end + datetime.timedelta(days=1))(when(ev))]
            for ev in events:
                ev["idLeague"] = lid
            path.write_text(json.dumps({"id": lid, "league": name, "updated": report["updated"], "stale": False, "events": events},
                                       ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            row["games"] = len(events)
        report["leagues"].append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    clubs.save()
    report["clubsSearched"], report["clubsKnown"] = clubs.searched, sum(1 for v in clubs.map.values() if v[0])
    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    if not any(r.get("games") for r in report["leagues"]):
        sys.exit("no league answered")


if __name__ == "__main__":
    main()
