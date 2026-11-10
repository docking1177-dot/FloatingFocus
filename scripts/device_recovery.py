"""Shared native selection path; every actual continuation remains ID-admitted by the service."""
from device_controls import wait_for


def open_recovery(d):
    # Lazy imports avoid the interactions -> stopwatch -> lifecycle import cycle.
    from device_interactions import tap_control
    tap_control(d, '继续计时 · ', prefix=True)
    assert any(n.get('text')=='继续计时' for n in d.dump().iter('node')), 'Recovery panel did not open'


def close_recovery(d):
    from device_interactions import tap_control
    tap_control(d, '关闭')


def choose_recovery(d, source_id):
    from device_interactions import control
    source=next(r for r in d.records() if r['id']==source_id)
    activity=source.get('stopwatch',{}).get('activity',{})
    ended=source['startedWallMillis']+activity.get('spanMillis',source.get('screenSpanMillis',source['elapsedMillis']))
    stamp=d.shell('date','-d','@'+str(ended//1000),"'+%Y-%m-%d %H:%M:%S'").strip()
    name=source['appName'].strip() or source['packageName'].strip() or '手动计时'
    before={r['id'] for r in d.records()}; overlay=d.overlay()
    open_recovery(d)
    for _ in range(18):
        tree=d.dump();parents={c:p for p in tree.iter('node') for c in p}
        matches={}
        for leaf in tree.iter('node'):
            if name not in leaf.get('text',''): continue
            row=leaf
            while row is not None:
                if row.get('clickable')=='true':
                    strings=[n.get('text','') for n in row.iter('node')]
                    if any(stamp in text for text in strings):
                        matches[d.bounds(row)]=row
                    break
                row=parents.get(row)
        if matches:
            assert len(matches)==1, 'Source name/checkpoint does not identify one actual candidate row'
            x,y,r,b=next(iter(matches));d.tapxy((x+r)//2,(y+b)//2)
            break
        # Stay inside the actual modal list rather than scrolling the page below.
        d.shell('input','swipe',d.width//2,int(d.height*.70),d.width//2,int(d.height*.48),300)
    else:
        raise AssertionError('Reliable candidate not reachable: '+source_id+' / '+name+' / '+stamp)
    assert {r['id'] for r in d.records()}==before, 'Selecting a recovery candidate started a segment'
    assert d.overlay()==overlay, 'Selecting a recovery candidate changed the real overlay'
    assert any('最后保存 '+stamp in n.get('text','') for n in d.dump().iter('node')), 'Selected details belong to another source'
    # The explicit button can be below a long detail in the bounded scrolling list.
    for _ in range(10):
        try:
            return control(d,'继续计时')
        except AssertionError:
            d.shell('input','swipe',d.width//2,int(d.height*.70),d.width//2,int(d.height*.52),250)
    raise AssertionError('Explicit continuation button not reachable for '+source_id)


def resume_recovery(d, source_id, duplicate=False):
    button=choose_recovery(d,source_id)
    assert button.get('enabled')=='true', 'Explicit recovery action is disabled'
    x,y,r,b=d.bounds(button);point=((x+r)//2,(y+b)//2)
    # Preserve the old duplicate-input regression, with coordinates from the
    # actual selected source action rather than a same-text dialog title.
    for _ in range(2 if duplicate else 1): d.shell('input','tap',*point)
    wait_for(lambda:d.overlay() and any(r.get('stopwatch',{}).get('continuationOf')==source_id for r in d.records()),
        'Explicit linked continuation did not start')
    children=[r for r in d.records() if r.get('stopwatch',{}).get('continuationOf')==source_id]
    assert len(children)==1, 'Duplicate continuation segments'
    close_recovery(d)
    return children[0]['id']
