from PIL import Image, ImageFilter
import numpy as np, sys
src=sys.argv[1]; out=sys.argv[2]
im=Image.open(src).convert('RGB'); d=np.asarray(im).astype(float)
H,W,_=d.shape; cy,cx=331.0,318.5   # the dial's centre, fitted to its rim
yy,xx=np.mgrid[0:H,0:W]; r=np.hypot(yy-cy,xx-cx)
R,G,B=d[...,0],d[...,1],d[...,2]; lum=d.mean(2)
clip=lambda v:np.clip(v,0,1)
navy=clip((B-R-3)/12)*clip((190-lum)/45)
dark=clip((120-lum)/40)                       # the bars' shadowed faces
gold=clip((R-B-22)/28)*clip((R-110)/40)
keep=np.maximum(np.maximum(navy,dark),gold)
dial=r<262
dial_r=262
emb=r<float(sys.argv[4]) if len(sys.argv)>4 else np.zeros_like(r,bool)
bgmask=(keep<0.15)&dial                  # the dial's colour is taken from clean dial only
keep=np.where(r<262, 0, keep)   # the whole dial is drawn again over its own colour   # the photographed emblem goes: it is drawn again
# the dial's own colour, smooth: a normalized blur of the clean background only
from scipy.ndimage import gaussian_filter, grey_erosion
def blur(a,s): return gaussian_filter(a,s)
w=bgmask.astype(float); bg=np.zeros_like(d)
for k in range(3):
    num=blur(d[...,k]*w,30); den=blur(w,30)+1e-6; bg[...,k]=num/den
# shrink the kept area's soft edge slightly so smudge halos go
k2=grey_erosion(keep,size=(3,3))
k2=np.maximum(k2, keep*(keep>0.6))
a=np.where(dial, k2, 1.0)[...,None]
# soften the dial's border into the frame
edge=clip((r-(dial_r-4))/4)[...,None]
a=np.maximum(a,edge)
res=d*a+bg*(1-a)
Image.fromarray(np.clip(res,0,255).astype(np.uint8)).save(out, quality=95)
