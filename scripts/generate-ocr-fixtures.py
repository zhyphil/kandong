#!/usr/bin/env python3
"""Render only project-authored fixture strings; never redistribute a font file."""
import argparse, hashlib, json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

def sha(data): return hashlib.sha256(data).hexdigest()
def render(source, font):
    width, padding = 640, 32
    lines=[]
    for paragraph in source.split('\n'):
        remaining=paragraph
        while remaining and font.getlength(remaining)>width-2*padding:
            cut=1
            while cut<len(remaining) and font.getlength(remaining[:cut+1])<=width-2*padding: cut+=1
            space=remaining.rfind(' ',0,cut+1)
            if space>0: cut=space
            lines.append(remaining[:cut]); remaining=remaining[cut:].lstrip(' ')
        lines.append(remaining)
    ascent,descent=font.getmetrics(); line_height=ascent+descent+4
    im=Image.new('RGBA',(width,max(1,len(lines))*line_height+2*padding),'white')
    draw=ImageDraw.Draw(im)
    for index,line in enumerate(lines):
        draw.text((padding,padding+index*line_height),line,font=font,fill='black',anchor='lt')
    return im,lines

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--spec',required=True); ap.add_argument('--font',required=True); ap.add_argument('--out',required=True)
    args=ap.parse_args(); spec_path=Path(args.spec); spec=json.loads(spec_path.read_text()); output=Path(args.out); output.mkdir(parents=True,exist_ok=True)
    assert spec['schema']==1 and len(spec['cases'])==10
    rows=[]
    pairs=[(case,size) for case in spec['cases'] for size in case['fontSizesPx']]
    pairs.append(({'id':'blank-negative','language':'none','source':''},24))
    for case,size in pairs:
        assert 0<=len(case['source'])<=256 and size in (16,24,32)
        im,lines=render(case['source'],ImageFont.truetype(args.font,size))
        name=f"{case['id']}-{size}.png"; p=output/name; im.save(p)
        rows.append(dict(case,fontPx=size,asset=name,width=im.width,height=im.height,pngSha256=sha(p.read_bytes()),pixelSha256=sha(im.tobytes()),renderedLines=lines))
    manifest={'schema':1,'fixtureVersion':spec['fixtureVersion'],'scope':spec['scope'],'pixelHashFormat':'row-major RGBA8888, no row padding, opaque alpha 255','specSha256':sha(spec_path.read_bytes()),'generator':{'pillow':pillow_version,'fontFamily':'Arial Unicode MS Regular','fontSha256':sha(Path(args.font).read_bytes()),'fontRedistributed':False,'widthPx':640,'paddingPx':32,'note':'Fixed raster fixtures are generated once, bundled unchanged on both devices. They are not the original Android native render.'},'images':rows}
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'images':len(rows),'tasks':len(rows)*8,'manifestSha256':sha((output/'manifest.json').read_bytes()),'pngBytes':sum((output/r['asset']).stat().st_size for r in rows)},indent=2))
if __name__=='__main__':main()
