"""Render FluxPay's original 12-second cross-border-wallet film and poster.

Requires Pillow, numpy and imageio-ffmpeg. No external image/video footage is used.
The world outline is public-domain Natural Earth data; see README.md.
"""
from pathlib import Path
import argparse
import json
import math
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / ".video-tools"))
import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H, FPS, DURATION = 1280, 720, 24, 12
OUT = PROJECT / "frontend/fluxpay-ui/src/css/media"
CX, CY, R = 895, 337, 274
FONT_DIR = Path("C:/Windows/Fonts")
FONTS = {}


def font(size, bold=False):
    key = (size, bold)
    if key not in FONTS:
        candidates = [FONT_DIR / ("segoeuib.ttf" if bold else "segoeui.ttf"),
                      Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
        selected = next((p for p in candidates if p.exists()), None)
        if not selected:
            raise RuntimeError("Install Segoe UI or DejaVu Sans to render the film.")
        FONTS[key] = ImageFont.truetype(str(selected), size)
    return FONTS[key]


def text(draw, at, value, size, fill="#f2f6ff", bold=False, spacing=3):
    draw.multiline_text(at, value, font=font(size, bold), fill=fill, spacing=spacing)


def roundbox(draw, box, fill, outline=None, radius=18, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def backdrop():
    y, x = np.mgrid[0:H, 0:W]
    glow = np.exp(-(((x-CX)/400)**2 + ((y-CY)/340)**2))
    base = np.zeros((H, W, 3), dtype=np.uint8)
    for c, (lo, hi) in enumerate([(7, 16), (13, 39), (25, 65)]):
        base[:, :, c] = lo + glow * hi
    image = Image.fromarray(base)
    # Physical sphere lighting, with a cool atmospheric edge.
    disc = np.sqrt(((x-CX)/R)**2 + ((y-CY)/R)**2)
    z = np.sqrt(np.maximum(0, 1-disc**2))
    light = np.clip(z*.5 + (CX-x)/R*.27 + (CY-y)/R*.2, 0, 1)
    sphere = np.zeros_like(base)
    for c, (lo, hi) in enumerate([(12, 23), (28, 49), (47, 65)]):
        sphere[:, :, c] = lo + light*hi
    array = np.array(image)
    array[disc <= 1] = sphere[disc <= 1]
    image = Image.fromarray(array)
    edge = Image.new("RGBA", (W, H))
    ed = ImageDraw.Draw(edge)
    ed.ellipse((CX-R, CY-R, CX+R, CY+R), outline=(95, 167, 234, 70), width=3)
    image = Image.alpha_composite(image.convert("RGBA"), edge.filter(ImageFilter.GaussianBlur(6)))
    return image


def land_points():
    data = json.loads(Path(__file__).with_name("ne_110m_land.geojson").read_text())
    mask = Image.new("1", (1440, 720))
    draw = ImageDraw.Draw(mask)
    for feature in data["features"]:
        geom = feature["geometry"]
        polygons = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        for polygon in polygons:
            for i, ring in enumerate(polygon):
                coords = [((lon+180)*4, (90-lat)*4) for lon, lat in ring]
                draw.polygon(coords, fill=1 if i == 0 else 0)
    points = []
    for lat in np.arange(-60, 83, 2.2):
        for lon in np.arange(-180, 180, 2.2/max(.4, math.cos(math.radians(lat)))):
            if mask.getpixel((min(1439, int((lon+180)*4)), min(719, int((90-lat)*4)))):
                points.append((lon, lat))
    return points


def project(lon, lat, rotation):
    lon, lat, tilt = map(math.radians, (lon-rotation, lat, 21))
    x = math.cos(lat)*math.sin(lon)
    y = math.cos(tilt)*math.sin(lat)-math.sin(tilt)*math.cos(lat)*math.cos(lon)
    z = math.sin(tilt)*math.sin(lat)+math.cos(tilt)*math.cos(lat)*math.cos(lon)
    return CX+R*x, CY-R*y, z


def visible_line(draw, points, rotation, fill, width=1):
    segment = []
    for lon, lat in points:
        x, y, z = project(lon, lat, rotation)
        if z > .015:
            segment.append((x, y))
        else:
            if len(segment)>1:
                draw.line(segment, fill=fill, width=width)
            segment=[]
    if len(segment)>1:
        draw.line(segment, fill=fill, width=width)


def curve(a, b, q, lift=80):
    control = ((a[0]+b[0])/2, min(a[1], b[1])-lift)
    return ((1-q)**2*a[0]+2*(1-q)*q*control[0]+q*q*b[0],
            (1-q)**2*a[1]+2*(1-q)*q*control[1]+q*q*b[1])


def wallet(image, x, y, code, name, symbol, tint):
    layer = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(layer)
    roundbox(d, (x, y, x+154, y+82), (224, 236, 249, 244), (255,255,255,200), 16)
    d.ellipse((x+13, y+15, x+50, y+52), fill=tint)
    text(d, (x+23, y+17), symbol, 22, "#284764")
    text(d, (x+62,y+14),code,19,"#19354e",True)
    text(d, (x+62,y+40),name,10,"#5c7388")
    d.line((x+15,y+66,x+138,y+66),fill=(164,184,204,130))
    return Image.alpha_composite(image,layer)


PHASES = [
    ("One wallet.\nA wider world.", "Hold USD, EUR and INR.\nKeep your currencies together.", "01  YOUR CURRENCIES"),
    ("Your route.\nYour choice.", "Compare fees and exchange rates.\nChoose before you confirm.", "02  YOUR OPTIONS"),
    ("Every move.\nIn view.", "Track your transfer’s journey.\nStay connected at every step.", "03  YOUR TRANSFER"),
]


def copy_layer(index, opacity, shift=0):
    layer=Image.new("RGBA",(W,H))
    d=ImageDraw.Draw(layer)
    heading, sub, label=PHASES[index]
    text(d,(72,178+shift),label,12,"#8faccc",True)
    text(d,(68,215+shift),heading,57,"#f6f8fd",False,0)
    text(d,(72,377+shift),sub,19,"#abbcd0",False,7)
    if opacity<1:
        layer.putalpha(layer.getchannel("A").point(lambda a:int(a*opacity)))
    return layer


def render(t, background, points):
    image=background.copy()
    draw=ImageDraw.Draw(image)
    rotation=7 + 12*math.sin(2*math.pi*t/DURATION)
    for lat in [-60,-30,0,30,60]:
        visible_line(draw,[(lon,lat) for lon in range(-180,181,3)],rotation,(76,116,153,75))
    for lon in range(-180,181,30):
        visible_line(draw,[(lon,lat) for lat in range(-85,86,3)],rotation,(76,116,153,75))
    for lon, lat in points:
        x,y,z=project(lon,lat,rotation)
        if z>.03:
            c=int(100+z*118)
            radius=1.0+z*.7
            draw.ellipse((x-radius,y-radius,x+radius,y+radius),fill=(int(c*.67),int(c*.86),c,255))
    nodes=[project(-74,40.7,rotation),project(2.35,48.86,rotation),project(72.88,19.08,rotation)]
    glow=Image.new("RGBA",(W,H))
    gd=ImageDraw.Draw(glow)
    for i,(a,b) in enumerate([(nodes[0],nodes[1]),(nodes[1],nodes[2]),(nodes[0],nodes[2])]):
        full=[curve(a,b,q,lift=65+i*25) for q in np.linspace(0,1,100)]
        draw.line(full,fill=(113,178,237,75),width=1)
        head=(t/4+i/3)%1
        trail=[curve(a,b,q,lift=65+i*25) for q in np.linspace(max(0,head-.18),head,24)]
        gd.line(trail,fill=(97,191,255,190),width=7)
        draw.line(trail,fill=(158,220,255,255),width=2)
        px,py=curve(a,b,head,lift=65+i*25)
        gd.ellipse((px-8,py-8,px+8,py+8),fill=(106,195,255,200))
        draw.ellipse((px-3,py-3,px+3,py+3),fill="#f1faff")
    image=Image.alpha_composite(image,glow.filter(ImageFilter.GaussianBlur(7)))
    draw=ImageDraw.Draw(image)
    for i,(x,y,z) in enumerate(nodes):
        pulse=9+7*((t/2+i/3)%1)
        draw.ellipse((x-pulse,y-pulse,x+pulse,y+pulse),outline=(130,204,255,110),width=1)
        draw.ellipse((x-4,y-4,x+4,y+4),fill="#d4eeff")
    bob=5*math.sin(2*math.pi*t/12)
    image=wallet(image,583,316+bob,"USD","US dollar","$",(196,221,247,255))
    image=wallet(image,850,86-bob,"EUR","Euro","€",(218,212,238,255))
    image=wallet(image,1050,371+bob,"INR","Indian rupee","₹",(192,222,213,255))
    draw=ImageDraw.Draw(image)
    text(draw,(72,54),"FluxPay",28,"#f3f7ff",True)
    # Original four-point brand accent.
    draw.polygon([(199,56),(203,66),(213,70),(203,74),(199,84),(195,74),(185,70),(195,66)],fill="#badbff")
    text(draw,(72,491),"MONEY WITHOUT BORDERS",10,"#7895b5",True)
    draw.line((72,520,449,520),fill="#223349")
    text(draw,(72,540),"USD",13,"#c5d9ed",True)
    text(draw,(164,540),"EUR",13,"#c5d9ed",True)
    text(draw,(256,540),"INR",13,"#c5d9ed",True)
    text(draw,(120,540),"↔",14,"#6d8daf")
    text(draw,(212,540),"↔",14,"#6d8daf")
    phase=int(t/4)%3
    local=t%4
    if local>3.2:
        q=(local-3.2)/.8
        q=q*q*(3-2*q)
        image=Image.alpha_composite(image,copy_layer(phase,1-q,-12*q))
        image=Image.alpha_composite(image,copy_layer((phase+1)%3,q,12*(1-q)))
    else:
        image=Image.alpha_composite(image,copy_layer(phase,1))
    draw=ImageDraw.Draw(image)
    labels=["Choose your wallet","Compare your routes","Follow your transfer"]
    for i,label in enumerate(labels):
        x=72+i*380
        draw.line((x,624,x+340,624),fill="#2e4259",width=2)
        progress=max(0,min(1,(t-i*4)/4))
        if phase==2 and local>3.2:
            progress*=1-(local-3.2)/.8
        if progress>0:
            draw.line((x,624,x+340*progress,624),fill="#a3d7ff",width=2)
        text(draw,(x,643),str(i+1).zfill(2),11,"#7393b4")
        text(draw,(x+30,639),label,14,"#d4e2f1" if phase==i else "#7791aa")
    text(draw,(72,688),"Illustrative flow · no real funds or transactions",9,"#627f9e")
    return image.convert("RGB")


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--poster-only",action="store_true")
    ap.add_argument("--mobile",action="store_true",help="Render the purpose-designed portrait cut")
    args=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    background,points=backdrop(),land_points()
    transform = mobile_frame if args.mobile else lambda frame,t:frame
    basename = "cross-border-wallet-mobile" if args.mobile else "cross-border-wallet"
    size = "720x960" if args.mobile else f"{W}x{H}"
    transform(render(1.7,background,points),1.7).save(OUT/(basename+".jpg"),quality=94)
    if args.poster_only:
        return
    binary=imageio_ffmpeg.get_ffmpeg_exe()
    command=[binary,"-y","-loglevel","error","-f","rawvideo","-vcodec","rawvideo",
             "-pix_fmt","rgb24","-s",size,"-r",str(FPS),"-i","-",
             "-an","-c:v","libx264","-preset","medium","-crf","21",
             "-pix_fmt","yuv420p","-movflags","+faststart",str(OUT/(basename+".mp4"))]
    with subprocess.Popen(command,stdin=subprocess.PIPE) as encoder:
        try:
            for frame in range(FPS*DURATION):
                encoder.stdin.write(transform(render(frame/FPS,background,points),frame/FPS).tobytes())
                if frame%FPS==0:
                    print(f"Rendered {frame//FPS}/{DURATION}s",flush=True)
        finally:
            encoder.stdin.close()
        if encoder.wait()!=0:
            raise RuntimeError("Video encoder failed")
    print(f"Created {OUT/(basename+'.mp4')}",flush=True)


def mobile_frame(frame,t):
    image=Image.new("RGB",(720,960),"#081221")
    image.paste(frame.crop((530,30,1250,600)),(0,238))
    phase=int(t/4)%3
    local=t%4
    def mobile_copy(index,alpha):
        layer=Image.new("RGBA",image.size)
        d=ImageDraw.Draw(layer)
        text(d,(39,72),PHASES[index][0],47,"#f3f7ff",False,0)
        # Compact, readable copy in the portrait cut.
        captions=["USD, EUR and INR. All together.","Compare fees. Choose your route.","Track the journey, step by step."]
        text(d,(40,201),captions[index],19,"#afc3da")
        layer.putalpha(layer.getchannel("A").point(lambda a:int(a*alpha)))
        return layer
    image=image.convert("RGBA")
    if local>3.2:
        q=(local-3.2)/.8;q=q*q*(3-2*q)
        image=Image.alpha_composite(image,mobile_copy(phase,1-q))
        image=Image.alpha_composite(image,mobile_copy((phase+1)%3,q))
    else:
        image=Image.alpha_composite(image,mobile_copy(phase,1))
    d=ImageDraw.Draw(image)
    text(d,(40,25),"FluxPay",20,"#c8e0f6",True)
    d.polygon([(135,28),(138,35),(145,38),(138,41),(135,48),(132,41),(125,38),(132,35)],fill="#badbff")
    text(d,(40,829),["01  YOUR CURRENCIES","02  YOUR OPTIONS","03  YOUR TRANSFER"][phase],12,"#9cbbda",True)
    for i in range(3):
        x=40+i*217
        d.line((x,870,x+190,870),fill="#2b435b",width=3)
        progress=max(0,min(1,(t-i*4)/4))
        if phase==2 and local>3.2:progress*=1-(local-3.2)/.8
        if progress>0:d.line((x,870,x+190*progress,870),fill="#acd9fa",width=3)
    text(d,(40,909),"Illustrative flow · no real funds or transactions",12,"#7f99b4")
    return image.convert("RGB")


if __name__=="__main__":
    main()
