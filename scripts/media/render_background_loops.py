"""Two original, six-second, center-safe wallet-transfer background films."""
import argparse
import math
import subprocess

from render_wallet_video import (
    OUT, W, H, Image, ImageDraw, ImageFilter, np, imageio_ffmpeg,
    land_points, text, roundbox
)

FPS, DURATION = 24, 6


def background(kind):
    y,x=np.mgrid[0:H,0:W]
    glow=np.exp(-(((x-640)/650)**2+((y-650)/460)**2))
    if kind=="possibility":
        palette=[(12,12),(54,52),(112,72)]
    else:
        palette=[(10,35),(15,37),(37,67)]
    pixels=np.zeros((H,W,3),dtype=np.uint8)
    for c,(low,high) in enumerate(palette):
        pixels[:,:,c]=low+high*glow
    return Image.fromarray(pixels).convert("RGBA")


def project(lon,lat,angle,kind):
    lon,lat,tilt=map(math.radians,(lon-angle,lat,18))
    x=math.cos(lat)*math.sin(lon)
    y=math.cos(tilt)*math.sin(lat)-math.sin(tilt)*math.cos(lat)*math.cos(lon)
    z=math.sin(tilt)*math.sin(lat)+math.cos(tilt)*math.cos(lat)*math.cos(lon)
    radius=480 if kind=="possibility" else 595
    center_y=685 if kind=="possibility" else 940
    return 640+radius*x,center_y-radius*y,z


def arc(a,b,q,lift):
    mid=((a[0]+b[0])/2,min(a[1],b[1])-lift)
    return ((1-q)**2*a[0]+2*q*(1-q)*mid[0]+q*q*b[0],
            (1-q)**2*a[1]+2*q*(1-q)*mid[1]+q*q*b[1])


def token(image,x,y,code,symbol,t,phase,kind):
    float_y=7*math.sin(t*2*math.pi/DURATION+phase)
    y+=float_y
    layer=Image.new("RGBA",(W,H))
    d=ImageDraw.Draw(layer)
    if kind=="possibility":
        roundbox(d,(x,y,x+144,y+75),(219,238,255,227),(255,255,255,180),19)
        d.ellipse((x+13,y+14,x+57,y+58),fill=(153,200,242,190))
        text(d,(x+26,y+18),symbol,26,"#254f7c")
        text(d,(x+73,y+26),code,18,"#214466",True)
    else:
        d.ellipse((x,y,x+110,y+110),fill=(208,222,247,240),outline=(255,255,255,200),width=2)
        d.ellipse((x+8,y+8,x+102,y+102),outline=(131,154,193,155),width=1)
        text(d,(x+36,y+18),symbol,48,"#647a9f")
        text(d,(x+42,y+78),code,10,"#7182a0",True)
    return Image.alpha_composite(image,layer)


def frame(t,kind,bg,points):
    image=bg.copy()
    draw=ImageDraw.Draw(image)
    angle=7+7*math.sin(t*2*math.pi/DURATION)
    # The map sits low in the composition, keeping the website copy unobstructed.
    for lat in [-30,0,30,60]:
        line=[]
        for lon in range(-160,161,3):
            x,y,z=project(lon,lat,angle,kind)
            if z>0:
                line.append((x,y))
            elif line:
                if len(line)>1:draw.line(line,fill=(121,187,239,50),width=1)
                line=[]
        if len(line)>1:draw.line(line,fill=(121,187,239,50),width=1)
    for lon,lat in points:
        x,y,z=project(lon,lat,angle,kind)
        if z>.02 and 0<y<H:
            r=1.1+1.1*z
            shade=int(100+100*z)
            draw.ellipse((x-r,y-r,x+r,y+r),fill=(int(shade*.6),int(shade*.85),shade,180))
    glow=Image.new("RGBA",(W,H));gd=ImageDraw.Draw(glow)
    if kind=="possibility":
        nodes=[(205,495),(985,452),(785,682)]
        routes=[(nodes[0],nodes[1],100),(nodes[1],nodes[2],70),(nodes[0],nodes[2],10)]
    else:
        nodes=[(173,480),(1100,453),(755,705)]
        routes=[(nodes[0],nodes[1],60),(nodes[1],nodes[2],25),(nodes[0],nodes[2],-20)]
    for i,(a,b,lift) in enumerate(routes):
        route=[arc(a,b,q,lift) for q in np.linspace(0,1,100)]
        draw.line(route,fill=(144,193,240,115),width=1)
        head=(t/3+i/3)%1
        # Periodic trains of light make the final frame connect smoothly to the first.
        for j in range(2):
            h=(head+j*.5)%1
            trail=[arc(a,b,q,lift) for q in np.linspace(max(0,h-.09),h,18)]
            gd.line(trail,fill=(158,220,255,180),width=7)
            draw.line(trail,fill=(192,232,255,255),width=2)
            x,y=arc(a,b,h,lift)
            gd.ellipse((x-7,y-7,x+7,y+7),fill=(182,225,255,200))
            draw.ellipse((x-2.5,y-2.5,x+2.5,y+2.5),fill="#f1faff")
    image=Image.alpha_composite(image,glow.filter(ImageFilter.GaussianBlur(7)))
    if kind=="possibility":
        positions=[(110,450,"USD","$"),(940,410,"EUR","€"),(728,647,"INR","₹")]
    else:
        positions=[(122,416,"USD","$"),(1045,396,"EUR","€"),(905,645,"INR","₹")]
    for i,(x,y,code,symbol) in enumerate(positions):
        image=token(image,x,y,code,symbol,t,i*2,kind)
    # Fine orbiting dots add depth without putting essential information in the video.
    d=ImageDraw.Draw(image)
    for i in range(14):
        a=2*math.pi*(i/14+t/DURATION)
        x=640+560*math.cos(a);y=540+95*math.sin(a)
        d.ellipse((x-1.3,y-1.3,x+1.3,y+1.3),fill=(170,213,255,100))
    return image.convert("RGB")


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--poster-only",action="store_true")
    args=ap.parse_args()
    points=land_points()
    OUT.mkdir(parents=True,exist_ok=True)
    for kind,name in [("possibility","wallet-possibility-loop"),("journey","wallet-journey-loop")]:
        bg=background(kind)
        frame(0,kind,bg,points).save(OUT/(name+".jpg"),quality=92)
        if args.poster_only:continue
        command=[imageio_ffmpeg.get_ffmpeg_exe(),"-y","-loglevel","error",
                 "-f","rawvideo","-pix_fmt","rgb24","-s",f"{W}x{H}","-r",str(FPS),
                 "-i","-","-an","-c:v","libx264","-preset","medium","-crf","25",
                 "-pix_fmt","yuv420p","-movflags","+faststart",str(OUT/(name+".mp4"))]
        with subprocess.Popen(command,stdin=subprocess.PIPE) as encoder:
            try:
                for n in range(FPS*DURATION):
                    encoder.stdin.write(frame(n/FPS,kind,bg,points).tobytes())
                    if n%FPS==0:print(f"{name}: {n//FPS}/{DURATION}s",flush=True)
            finally:
                encoder.stdin.close()
            if encoder.wait()!=0:raise RuntimeError("Encoding failed")
        print(f"Saved {name}.mp4",flush=True)


if __name__=="__main__":
    main()
