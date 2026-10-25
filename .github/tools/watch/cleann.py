# the night face: the same cleaning, its bars white on a green dial
from PIL import Image
import numpy as np, sys
from scipy.ndimage import gaussian_filter, grey_erosion
d=np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(float)
H,W,_=d.shape; cy,cx=331.0,318.5   # the dial's centre, fitted to its rim
yy,xx=np.mgrid[0:H,0:W]; r=np.hypot(yy-cy,xx-cx)
R,G,B=d[...,0],d[...,1],d[...,2]; lum=d.mean(2)
clip=lambda v:np.clip(v,0,1)
white=clip((lum-150)/40)*clip((40-(G-np.minimum(R,B)))/30)
gold=clip((R-B-30)/30)*clip((R-120)/40)
keep=np.maximum(white,gold)
dial=r<262; emb=r<140
bgmask=(keep<0.15)&dial
keep=np.where(r<262, 0, keep)   # the whole dial is drawn again over its own colour
w=bgmask.astype(float); bg=np.zeros_like(d)
for k in range(3): bg[...,k]=gaussian_filter(d[...,k]*w,90)/(gaussian_filter(w,90)+1e-6)
k2=np.maximum(grey_erosion(keep,size=(3,3)), keep*(keep>0.6))
a=np.where(dial,k2,1.0)[...,None]; a=np.maximum(a,clip((r-259)/3)[...,None])
Image.fromarray(np.clip(d*a+bg*(1-a),0,255).astype(np.uint8)).save(sys.argv[2],quality=95)
