import struct, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src); f = open(src,'rb'); out = open(dst,'wb'); cd=[]
for i in z.infolist():
    f.seek(i.header_offset); h=f.read(30)
    nlen,elen=struct.unpack('<HH',h[26:30]); name=f.read(nlen); f.read(elen)
    data=f.read(i.compress_size)
    off=out.tell(); extra=b''
    if i.compress_type==0:
        al=4096 if i.filename.endswith('.so') else 4
        pad=(al-(off+30+len(name))%al)%al
        extra=b'\0'*pad
    flags=i.flag_bits & ~0x08
    dt=h[10:14]
    out.write(struct.pack('<IHHHH',0x04034b50,20,flags,i.compress_type,0)[:10]+dt+struct.pack('<III',i.CRC,i.compress_size,i.file_size)+struct.pack('<HH',len(name),len(extra))+name+extra+data)
    cd.append(struct.pack('<IHHHH',0x02014b50,20,20,flags,i.compress_type)+dt+struct.pack('<IIIHHHHHII',i.CRC,i.compress_size,i.file_size,len(name),0,0,0,0,i.external_attr,off)+name)
cs=out.tell(); out.write(b''.join(cd)); ce=out.tell()
out.write(struct.pack('<IHHHHIIH',0x06054b50,0,0,len(cd),len(cd),ce-cs,cs,0)); out.close()
