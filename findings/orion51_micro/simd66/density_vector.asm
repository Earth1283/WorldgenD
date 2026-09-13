
DensityVector.apply C2 bytes:     file format binary


Disassembly of section .data:

00007f48906eb600 <.data>:
    7f48906eb600:	89 84 24 00 c0 fe ff 	mov    DWORD PTR [rsp-0x14000],eax
    7f48906eb607:	55                   	push   rbp
    7f48906eb608:	48 83 ec 40          	sub    rsp,0x40
    7f48906eb60c:	41 81 7f 20 00 00 00 	cmp    DWORD PTR [r15+0x20],0x0
    7f48906eb613:	00 
    7f48906eb614:	0f 85 a0 12 00 00    	jne    0x7f48906ec8ba
    7f48906eb61a:	8b 5e 0c             	mov    ebx,DWORD PTR [rsi+0xc]
    7f48906eb61d:	8b 6a 0c             	mov    ebp,DWORD PTR [rdx+0xc]
    7f48906eb620:	8b cb                	mov    ecx,ebx
    7f48906eb622:	83 e1 fc             	and    ecx,0xfffffffc
    7f48906eb625:	83 fd 0a             	cmp    ebp,0xa
    7f48906eb628:	0f 83 80 00 00 00    	jae    0x7f48906eb6ae
    7f48906eb62e:	49 ba a8 5d 28 2a 06 	movabs r10,0x62a285da8
    7f48906eb635:	00 00 00 
    7f48906eb638:	41 8b 6c aa 10       	mov    ebp,DWORD PTR [r10+rbp*4+0x10]
    7f48906eb63d:	44 8d 55 ff          	lea    r10d,[rbp-0x1]
    7f48906eb641:	41 83 fa 0a          	cmp    r10d,0xa
    7f48906eb645:	0f 83 91 00 00 00    	jae    0x7f48906eb6dc
    7f48906eb64b:	48 89 14 24          	mov    QWORD PTR [rsp],rdx
    7f48906eb64f:	4d 63 d2             	movsxd r10,r10d
    7f48906eb652:	4c 63 c9             	movsxd r9,ecx
    7f48906eb655:	49 c1 e2 03          	shl    r10,0x3
    7f48906eb659:	49 83 c1 03          	add    r9,0x3
    7f48906eb65d:	44 8d 59 e4          	lea    r11d,[rcx-0x1c]
    7f48906eb661:	49 83 e1 fc          	and    r9,0xfffffffffffffffc
    7f48906eb665:	44 8d 41 f4          	lea    r8d,[rcx-0xc]
    7f48906eb669:	41 8b f9             	mov    edi,r9d
    7f48906eb66c:	44 8d 4b fd          	lea    r9d,[rbx-0x3]
    7f48906eb670:	4c 63 ef             	movsxd r13,edi
    7f48906eb673:	4c 63 f3             	movsxd r14,ebx
    7f48906eb676:	49 83 c5 fc          	add    r13,0xfffffffffffffffc
    7f48906eb67a:	49 83 c6 fd          	add    r14,0xfffffffffffffffd
    7f48906eb67e:	c4 e2 7d 19 e0       	vbroadcastsd ymm4,xmm0
    7f48906eb683:	c4 62 7d 19 0d f4 fe 	vbroadcastsd ymm9,QWORD PTR [rip+0xfffffffffffffef4]        # 0x7f48906eb580
    7f48906eb68a:	ff ff 
    7f48906eb68c:	bf 80 3e 00 00       	mov    edi,0x3e80
    7f48906eb691:	b8 00 7d 00 00       	mov    eax,0x7d00
    7f48906eb696:	c5 d0 57 ed          	vxorps xmm5,xmm5,xmm5
    7f48906eb69a:	ba 04 00 00 00       	mov    edx,0x4
    7f48906eb69f:	48 bd b0 b5 6e 90 48 	movabs rbp,0x7f48906eb5b0
    7f48906eb6a6:	7f 00 00 
    7f48906eb6a9:	42 ff 64 15 00       	jmp    QWORD PTR [rbp+r10*1+0x0]
    7f48906eb6ae:	48 89 34 24          	mov    QWORD PTR [rsp],rsi
    7f48906eb6b2:	48 89 54 24 08       	mov    QWORD PTR [rsp+0x8],rdx
    7f48906eb6b7:	c5 fb 11 44 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm0
    7f48906eb6bd:	c5 fb 11 4c 24 18    	vmovsd QWORD PTR [rsp+0x18],xmm1
    7f48906eb6c3:	89 4c 24 20          	mov    DWORD PTR [rsp+0x20],ecx
    7f48906eb6c7:	be e4 ff ff ff       	mov    esi,0xffffffe4
    7f48906eb6cc:	c5 f8 77             	vzeroupper
    7f48906eb6cf:	e8 0c 42 fa ff       	call   0x7f489068f8e0
    7f48906eb6d4:	0f 1f 84 00 4c 02 00 	nop    DWORD PTR [rax+rax*1+0x24c]
    7f48906eb6db:	00 
    7f48906eb6dc:	48 89 34 24          	mov    QWORD PTR [rsp],rsi
    7f48906eb6e0:	48 89 54 24 08       	mov    QWORD PTR [rsp+0x8],rdx
    7f48906eb6e5:	c5 fb 11 44 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm0
    7f48906eb6eb:	c5 fb 11 4c 24 18    	vmovsd QWORD PTR [rsp+0x18],xmm1
    7f48906eb6f1:	89 4c 24 20          	mov    DWORD PTR [rsp+0x20],ecx
    7f48906eb6f5:	be 45 ff ff ff       	mov    esi,0xffffff45
    7f48906eb6fa:	66 90                	xchg   ax,ax
    7f48906eb6fc:	c5 f8 77             	vzeroupper
    7f48906eb6ff:	e8 dc 41 fa ff       	call   0x7f489068f8e0
    7f48906eb704:	0f 1f 84 00 7c 02 00 	nop    DWORD PTR [rax+rax*1+0x100027c]
    7f48906eb70b:	01 
    7f48906eb70c:	85 c9                	test   ecx,ecx
    7f48906eb70e:	0f 8e 06 0e 00 00    	jle    0x7f48906ec51a
    7f48906eb714:	45 85 c9             	test   r9d,r9d
    7f48906eb717:	0f 8c 8f 0f 00 00    	jl     0x7f48906ec6ac
    7f48906eb71d:	83 fb 03             	cmp    ebx,0x3
    7f48906eb720:	0f 84 86 0f 00 00    	je     0x7f48906ec6ac
    7f48906eb726:	4d 3b ee             	cmp    r13,r14
    7f48906eb729:	0f 83 7d 0f 00 00    	jae    0x7f48906ec6ac
    7f48906eb72f:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906eb735:	0f 8f 01 11 00 00    	jg     0x7f48906ec83c
    7f48906eb73b:	c5 dd 58 56 10       	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+0x10]
    7f48906eb740:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906eb745:	41 83 fb 04          	cmp    r11d,0x4
    7f48906eb749:	0f 8e c5 00 00 00    	jle    0x7f48906eb814
    7f48906eb74f:	44 8b c1             	mov    r8d,ecx
    7f48906eb752:	44 2b c2             	sub    r8d,edx
    7f48906eb755:	41 83 c0 e4          	add    r8d,0xffffffe4
    7f48906eb759:	45 33 c9             	xor    r9d,r9d
    7f48906eb75c:	44 3b da             	cmp    r11d,edx
    7f48906eb75f:	45 0f 4c c1          	cmovl  r8d,r9d
    7f48906eb763:	41 81 f8 00 7d 00 00 	cmp    r8d,0x7d00
    7f48906eb76a:	44 0f 47 c0          	cmova  r8d,eax
    7f48906eb76e:	44 03 c2             	add    r8d,edx
    7f48906eb771:	0f 1f 84 00 00 00 00 	nop    DWORD PTR [rax+rax*1+0x0]
    7f48906eb778:	00 
    7f48906eb779:	0f 1f 80 00 00 00 00 	nop    DWORD PTR [rax+0x0]
    7f48906eb780:	c5 dd 58 54 d6 10    	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eb786:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eb78c:	c5 dd 58 54 d6 30    	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906eb792:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906eb798:	c5 dd 58 54 d6 50    	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906eb79e:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906eb7a4:	c5 dd 58 54 d6 70    	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906eb7aa:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906eb7b0:	c5 dd 58 94 d6 90 00 	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x90]
    7f48906eb7b7:	00 00 
    7f48906eb7b9:	c5 fe 7f 94 d6 90 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0x90],ymm2
    7f48906eb7c0:	00 00 
    7f48906eb7c2:	c5 dd 58 94 d6 b0 00 	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xb0]
    7f48906eb7c9:	00 00 
    7f48906eb7cb:	c5 fe 7f 94 d6 b0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xb0],ymm2
    7f48906eb7d2:	00 00 
    7f48906eb7d4:	c5 dd 58 94 d6 d0 00 	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xd0]
    7f48906eb7db:	00 00 
    7f48906eb7dd:	c5 fe 7f 94 d6 d0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xd0],ymm2
    7f48906eb7e4:	00 00 
    7f48906eb7e6:	c5 dd 58 94 d6 f0 00 	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xf0]
    7f48906eb7ed:	00 00 
    7f48906eb7ef:	c5 fe 7f 94 d6 f0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xf0],ymm2
    7f48906eb7f6:	00 00 
    7f48906eb7f8:	83 c2 20             	add    edx,0x20
    7f48906eb7fb:	41 3b d0             	cmp    edx,r8d
    7f48906eb7fe:	0f 8c 7c ff ff ff    	jl     0x7f48906eb780
    7f48906eb804:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906eb808:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906eb80b:	41 3b d3             	cmp    edx,r11d
    7f48906eb80e:	0f 8c 3b ff ff ff    	jl     0x7f48906eb74f
    7f48906eb814:	3b d1                	cmp    edx,ecx
    7f48906eb816:	0f 8d fe 0c 00 00    	jge    0x7f48906ec51a
    7f48906eb81c:	c5 dd 58 54 d6 10    	vaddpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eb822:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eb828:	83 c2 04             	add    edx,0x4
    7f48906eb82b:	3b d1                	cmp    edx,ecx
    7f48906eb82d:	7c ed                	jl     0x7f48906eb81c
    7f48906eb82f:	e9 e6 0c 00 00       	jmp    0x7f48906ec51a
    7f48906eb834:	85 c9                	test   ecx,ecx
    7f48906eb836:	0f 8e de 0c 00 00    	jle    0x7f48906ec51a
    7f48906eb83c:	45 85 c9             	test   r9d,r9d
    7f48906eb83f:	0f 8c 3f 0e 00 00    	jl     0x7f48906ec684
    7f48906eb845:	83 fb 03             	cmp    ebx,0x3
    7f48906eb848:	0f 84 36 0e 00 00    	je     0x7f48906ec684
    7f48906eb84e:	4d 3b ee             	cmp    r13,r14
    7f48906eb851:	0f 83 2d 0e 00 00    	jae    0x7f48906ec684
    7f48906eb857:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906eb85d:	0f 8f b1 0f 00 00    	jg     0x7f48906ec814
    7f48906eb863:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906eb868:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb86c:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb870:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906eb875:	41 83 f8 04          	cmp    r8d,0x4
    7f48906eb879:	0f 8e 99 00 00 00    	jle    0x7f48906eb918
    7f48906eb87f:	44 8b d9             	mov    r11d,ecx
    7f48906eb882:	44 2b da             	sub    r11d,edx
    7f48906eb885:	41 83 c3 f4          	add    r11d,0xfffffff4
    7f48906eb889:	45 33 c9             	xor    r9d,r9d
    7f48906eb88c:	44 3b c2             	cmp    r8d,edx
    7f48906eb88f:	45 0f 4c d9          	cmovl  r11d,r9d
    7f48906eb893:	41 81 fb 80 3e 00 00 	cmp    r11d,0x3e80
    7f48906eb89a:	44 0f 47 df          	cmova  r11d,edi
    7f48906eb89e:	44 03 da             	add    r11d,edx
    7f48906eb8a1:	0f 1f 84 00 00 00 00 	nop    DWORD PTR [rax+rax*1+0x0]
    7f48906eb8a8:	00 
    7f48906eb8a9:	0f 1f 80 00 00 00 00 	nop    DWORD PTR [rax+0x0]
    7f48906eb8b0:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eb8b6:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb8ba:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb8be:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eb8c4:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906eb8ca:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb8ce:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb8d2:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906eb8d8:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906eb8de:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb8e2:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb8e6:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906eb8ec:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906eb8f2:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb8f6:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb8fa:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906eb900:	83 c2 10             	add    edx,0x10
    7f48906eb903:	41 3b d3             	cmp    edx,r11d
    7f48906eb906:	7c a8                	jl     0x7f48906eb8b0
    7f48906eb908:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906eb90c:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906eb90f:	41 3b d0             	cmp    edx,r8d
    7f48906eb912:	0f 8c 67 ff ff ff    	jl     0x7f48906eb87f
    7f48906eb918:	3b d1                	cmp    edx,ecx
    7f48906eb91a:	0f 8d fa 0b 00 00    	jge    0x7f48906ec51a
    7f48906eb920:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eb926:	c5 ed 59 da          	vmulpd ymm3,ymm2,ymm2
    7f48906eb92a:	c5 e5 59 d2          	vmulpd ymm2,ymm3,ymm2
    7f48906eb92e:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eb934:	83 c2 04             	add    edx,0x4
    7f48906eb937:	3b d1                	cmp    edx,ecx
    7f48906eb939:	7c e5                	jl     0x7f48906eb920
    7f48906eb93b:	e9 da 0b 00 00       	jmp    0x7f48906ec51a
    7f48906eb940:	85 c9                	test   ecx,ecx
    7f48906eb942:	0f 8e d2 0b 00 00    	jle    0x7f48906ec51a
    7f48906eb948:	45 85 c9             	test   r9d,r9d
    7f48906eb94b:	0f 8c 0b 0d 00 00    	jl     0x7f48906ec65c
    7f48906eb951:	83 fb 03             	cmp    ebx,0x3
    7f48906eb954:	0f 84 02 0d 00 00    	je     0x7f48906ec65c
    7f48906eb95a:	4d 3b ee             	cmp    r13,r14
    7f48906eb95d:	0f 83 f9 0c 00 00    	jae    0x7f48906ec65c
    7f48906eb963:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906eb969:	0f 8f 7d 0e 00 00    	jg     0x7f48906ec7ec
    7f48906eb96f:	c5 b5 5e 56 10       	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+0x10]
    7f48906eb974:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906eb979:	41 83 fb 04          	cmp    r11d,0x4
    7f48906eb97d:	0f 8e c1 00 00 00    	jle    0x7f48906eba44
    7f48906eb983:	44 8b c1             	mov    r8d,ecx
    7f48906eb986:	44 2b c2             	sub    r8d,edx
    7f48906eb989:	41 83 c0 e4          	add    r8d,0xffffffe4
    7f48906eb98d:	45 33 c9             	xor    r9d,r9d
    7f48906eb990:	44 3b da             	cmp    r11d,edx
    7f48906eb993:	45 0f 4c c1          	cmovl  r8d,r9d
    7f48906eb997:	41 81 f8 00 7d 00 00 	cmp    r8d,0x7d00
    7f48906eb99e:	44 0f 47 c0          	cmova  r8d,eax
    7f48906eb9a2:	44 03 c2             	add    r8d,edx
    7f48906eb9a5:	66 66 66 0f 1f 84 00 	data16 data16 nop WORD PTR [rax+rax*1+0x0]
    7f48906eb9ac:	00 00 00 00 
    7f48906eb9b0:	c5 b5 5e 54 d6 10    	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eb9b6:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eb9bc:	c5 b5 5e 54 d6 30    	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906eb9c2:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906eb9c8:	c5 b5 5e 54 d6 50    	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906eb9ce:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906eb9d4:	c5 b5 5e 54 d6 70    	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906eb9da:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906eb9e0:	c5 b5 5e 94 d6 90 00 	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x90]
    7f48906eb9e7:	00 00 
    7f48906eb9e9:	c5 fe 7f 94 d6 90 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0x90],ymm2
    7f48906eb9f0:	00 00 
    7f48906eb9f2:	c5 b5 5e 94 d6 b0 00 	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0xb0]
    7f48906eb9f9:	00 00 
    7f48906eb9fb:	c5 fe 7f 94 d6 b0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xb0],ymm2
    7f48906eba02:	00 00 
    7f48906eba04:	c5 b5 5e 94 d6 d0 00 	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0xd0]
    7f48906eba0b:	00 00 
    7f48906eba0d:	c5 fe 7f 94 d6 d0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xd0],ymm2
    7f48906eba14:	00 00 
    7f48906eba16:	c5 b5 5e 94 d6 f0 00 	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0xf0]
    7f48906eba1d:	00 00 
    7f48906eba1f:	c5 fe 7f 94 d6 f0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xf0],ymm2
    7f48906eba26:	00 00 
    7f48906eba28:	83 c2 20             	add    edx,0x20
    7f48906eba2b:	41 3b d0             	cmp    edx,r8d
    7f48906eba2e:	0f 8c 7c ff ff ff    	jl     0x7f48906eb9b0
    7f48906eba34:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906eba38:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906eba3b:	41 3b d3             	cmp    edx,r11d
    7f48906eba3e:	0f 8c 3f ff ff ff    	jl     0x7f48906eb983
    7f48906eba44:	3b d1                	cmp    edx,ecx
    7f48906eba46:	0f 8d ce 0a 00 00    	jge    0x7f48906ec51a
    7f48906eba4c:	c5 b5 5e 54 d6 10    	vdivpd ymm2,ymm9,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906eba52:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906eba58:	83 c2 04             	add    edx,0x4
    7f48906eba5b:	3b d1                	cmp    edx,ecx
    7f48906eba5d:	7c ed                	jl     0x7f48906eba4c
    7f48906eba5f:	e9 b6 0a 00 00       	jmp    0x7f48906ec51a
    7f48906eba64:	85 c9                	test   ecx,ecx
    7f48906eba66:	0f 8e ae 0a 00 00    	jle    0x7f48906ec51a
    7f48906eba6c:	45 85 c9             	test   r9d,r9d
    7f48906eba6f:	0f 8c bf 0b 00 00    	jl     0x7f48906ec634
    7f48906eba75:	83 fb 03             	cmp    ebx,0x3
    7f48906eba78:	0f 84 b6 0b 00 00    	je     0x7f48906ec634
    7f48906eba7e:	4d 3b ee             	cmp    r13,r14
    7f48906eba81:	0f 83 ad 0b 00 00    	jae    0x7f48906ec634
    7f48906eba87:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906eba8d:	0f 8f 31 0d 00 00    	jg     0x7f48906ec7c4
    7f48906eba93:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906eba98:	c5 ed c2 dd 0e       	vcmpgtpd ymm3,ymm2,ymm5
    7f48906eba9d:	c4 e2 7d 19 35 e2 fa 	vbroadcastsd ymm6,QWORD PTR [rip+0xfffffffffffffae2]        # 0x7f48906eb588
    7f48906ebaa4:	ff ff 
    7f48906ebaa6:	c5 cd 59 e2          	vmulpd ymm4,ymm6,ymm2
    7f48906ebaaa:	c4 e3 5d 4a d2 30    	vblendvps ymm2,ymm4,ymm2,ymm3
    7f48906ebab0:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ebab5:	41 83 f8 04          	cmp    r8d,0x4
    7f48906ebab9:	0f 8e b5 00 00 00    	jle    0x7f48906ebb74
    7f48906ebabf:	44 8b d9             	mov    r11d,ecx
    7f48906ebac2:	44 2b da             	sub    r11d,edx
    7f48906ebac5:	41 83 c3 f4          	add    r11d,0xfffffff4
    7f48906ebac9:	45 33 c9             	xor    r9d,r9d
    7f48906ebacc:	44 3b c2             	cmp    r8d,edx
    7f48906ebacf:	45 0f 4c d9          	cmovl  r11d,r9d
    7f48906ebad3:	41 81 fb 80 3e 00 00 	cmp    r11d,0x3e80
    7f48906ebada:	44 0f 47 df          	cmova  r11d,edi
    7f48906ebade:	44 03 da             	add    r11d,edx
    7f48906ebae1:	0f 1f 84 00 00 00 00 	nop    DWORD PTR [rax+rax*1+0x0]
    7f48906ebae8:	00 
    7f48906ebae9:	0f 1f 80 00 00 00 00 	nop    DWORD PTR [rax+0x0]
    7f48906ebaf0:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebaf6:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebafa:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebaff:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebb05:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebb0b:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ebb11:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebb15:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebb1a:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebb20:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ebb26:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ebb2c:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebb30:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebb35:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebb3b:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ebb41:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ebb47:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebb4b:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebb50:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebb56:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ebb5c:	83 c2 10             	add    edx,0x10
    7f48906ebb5f:	41 3b d3             	cmp    edx,r11d
    7f48906ebb62:	7c 8c                	jl     0x7f48906ebaf0
    7f48906ebb64:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ebb68:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ebb6b:	41 3b d0             	cmp    edx,r8d
    7f48906ebb6e:	0f 8c 4b ff ff ff    	jl     0x7f48906ebabf
    7f48906ebb74:	3b d1                	cmp    edx,ecx
    7f48906ebb76:	0f 8d 9e 09 00 00    	jge    0x7f48906ec51a
    7f48906ebb7c:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebb82:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebb86:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebb8b:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebb91:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebb97:	83 c2 04             	add    edx,0x4
    7f48906ebb9a:	3b d1                	cmp    edx,ecx
    7f48906ebb9c:	7c de                	jl     0x7f48906ebb7c
    7f48906ebb9e:	e9 77 09 00 00       	jmp    0x7f48906ec51a
    7f48906ebba3:	85 c9                	test   ecx,ecx
    7f48906ebba5:	0f 8e 6f 09 00 00    	jle    0x7f48906ec51a
    7f48906ebbab:	45 85 c9             	test   r9d,r9d
    7f48906ebbae:	0f 8c 58 0a 00 00    	jl     0x7f48906ec60c
    7f48906ebbb4:	83 fb 03             	cmp    ebx,0x3
    7f48906ebbb7:	0f 84 4f 0a 00 00    	je     0x7f48906ec60c
    7f48906ebbbd:	4d 3b ee             	cmp    r13,r14
    7f48906ebbc0:	0f 83 46 0a 00 00    	jae    0x7f48906ec60c
    7f48906ebbc6:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ebbcc:	0f 8f ca 0b 00 00    	jg     0x7f48906ec79c
    7f48906ebbd2:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906ebbd7:	c5 ed 54 15 81 5f f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85f81]        # 0x7f4890671b60
    7f48906ebbde:	ff 
    7f48906ebbdf:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ebbe4:	41 83 fb 04          	cmp    r11d,0x4
    7f48906ebbe8:	0f 8e f6 00 00 00    	jle    0x7f48906ebce4
    7f48906ebbee:	44 8b c1             	mov    r8d,ecx
    7f48906ebbf1:	44 2b c2             	sub    r8d,edx
    7f48906ebbf4:	41 83 c0 e4          	add    r8d,0xffffffe4
    7f48906ebbf8:	45 33 c9             	xor    r9d,r9d
    7f48906ebbfb:	44 3b da             	cmp    r11d,edx
    7f48906ebbfe:	45 0f 4c c1          	cmovl  r8d,r9d
    7f48906ebc02:	41 81 f8 00 7d 00 00 	cmp    r8d,0x7d00
    7f48906ebc09:	44 0f 47 c0          	cmova  r8d,eax
    7f48906ebc0d:	44 03 c2             	add    r8d,edx
    7f48906ebc10:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebc16:	c5 ed 54 15 42 5f f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85f42]        # 0x7f4890671b60
    7f48906ebc1d:	ff 
    7f48906ebc1e:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebc24:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ebc2a:	c5 ed 54 15 2e 5f f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85f2e]        # 0x7f4890671b60
    7f48906ebc31:	ff 
    7f48906ebc32:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ebc38:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ebc3e:	c5 ed 54 15 1a 5f f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85f1a]        # 0x7f4890671b60
    7f48906ebc45:	ff 
    7f48906ebc46:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ebc4c:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ebc52:	c5 ed 54 15 06 5f f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85f06]        # 0x7f4890671b60
    7f48906ebc59:	ff 
    7f48906ebc5a:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ebc60:	c5 fc 10 94 d6 90 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x90]
    7f48906ebc67:	00 00 
    7f48906ebc69:	c5 ed 54 15 ef 5e f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85eef]        # 0x7f4890671b60
    7f48906ebc70:	ff 
    7f48906ebc71:	c5 fe 7f 94 d6 90 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0x90],ymm2
    7f48906ebc78:	00 00 
    7f48906ebc7a:	c5 fc 10 94 d6 b0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xb0]
    7f48906ebc81:	00 00 
    7f48906ebc83:	c5 ed 54 15 d5 5e f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85ed5]        # 0x7f4890671b60
    7f48906ebc8a:	ff 
    7f48906ebc8b:	c5 fe 7f 94 d6 b0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xb0],ymm2
    7f48906ebc92:	00 00 
    7f48906ebc94:	c5 fc 10 94 d6 d0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xd0]
    7f48906ebc9b:	00 00 
    7f48906ebc9d:	c5 ed 54 15 bb 5e f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85ebb]        # 0x7f4890671b60
    7f48906ebca4:	ff 
    7f48906ebca5:	c5 fe 7f 94 d6 d0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xd0],ymm2
    7f48906ebcac:	00 00 
    7f48906ebcae:	c5 fc 10 94 d6 f0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xf0]
    7f48906ebcb5:	00 00 
    7f48906ebcb7:	c5 ed 54 15 a1 5e f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85ea1]        # 0x7f4890671b60
    7f48906ebcbe:	ff 
    7f48906ebcbf:	c5 fe 7f 94 d6 f0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xf0],ymm2
    7f48906ebcc6:	00 00 
    7f48906ebcc8:	83 c2 20             	add    edx,0x20
    7f48906ebccb:	41 3b d0             	cmp    edx,r8d
    7f48906ebcce:	0f 8c 3c ff ff ff    	jl     0x7f48906ebc10
    7f48906ebcd4:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ebcd8:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ebcdb:	41 3b d3             	cmp    edx,r11d
    7f48906ebcde:	0f 8c 0a ff ff ff    	jl     0x7f48906ebbee
    7f48906ebce4:	3b d1                	cmp    edx,ecx
    7f48906ebce6:	0f 8d 2e 08 00 00    	jge    0x7f48906ec51a
    7f48906ebcec:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebcf2:	c5 ed 54 15 66 5e f8 	vandpd ymm2,ymm2,YMMWORD PTR [rip+0xfffffffffff85e66]        # 0x7f4890671b60
    7f48906ebcf9:	ff 
    7f48906ebcfa:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebd00:	83 c2 04             	add    edx,0x4
    7f48906ebd03:	3b d1                	cmp    edx,ecx
    7f48906ebd05:	7c e5                	jl     0x7f48906ebcec
    7f48906ebd07:	e9 0e 08 00 00       	jmp    0x7f48906ec51a
    7f48906ebd0c:	85 c9                	test   ecx,ecx
    7f48906ebd0e:	0f 8e 06 08 00 00    	jle    0x7f48906ec51a
    7f48906ebd14:	45 85 c9             	test   r9d,r9d
    7f48906ebd17:	0f 8c c7 08 00 00    	jl     0x7f48906ec5e4
    7f48906ebd1d:	83 fb 03             	cmp    ebx,0x3
    7f48906ebd20:	0f 84 be 08 00 00    	je     0x7f48906ec5e4
    7f48906ebd26:	4d 3b ee             	cmp    r13,r14
    7f48906ebd29:	0f 83 b5 08 00 00    	jae    0x7f48906ec5e4
    7f48906ebd2f:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ebd35:	0f 8f 39 0a 00 00    	jg     0x7f48906ec774
    7f48906ebd3b:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906ebd40:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebd44:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ebd49:	41 83 fb 04          	cmp    r11d,0x4
    7f48906ebd4d:	0f 8e e1 00 00 00    	jle    0x7f48906ebe34
    7f48906ebd53:	44 8b c1             	mov    r8d,ecx
    7f48906ebd56:	44 2b c2             	sub    r8d,edx
    7f48906ebd59:	41 83 c0 e4          	add    r8d,0xffffffe4
    7f48906ebd5d:	45 33 c9             	xor    r9d,r9d
    7f48906ebd60:	44 3b da             	cmp    r11d,edx
    7f48906ebd63:	45 0f 4c c1          	cmovl  r8d,r9d
    7f48906ebd67:	41 81 f8 00 7d 00 00 	cmp    r8d,0x7d00
    7f48906ebd6e:	44 0f 47 c0          	cmova  r8d,eax
    7f48906ebd72:	44 03 c2             	add    r8d,edx
    7f48906ebd75:	66 66 66 0f 1f 84 00 	data16 data16 nop WORD PTR [rax+rax*1+0x0]
    7f48906ebd7c:	00 00 00 00 
    7f48906ebd80:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebd86:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebd8a:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebd90:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ebd96:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebd9a:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ebda0:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ebda6:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebdaa:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ebdb0:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ebdb6:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebdba:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ebdc0:	c5 fc 10 94 d6 90 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x90]
    7f48906ebdc7:	00 00 
    7f48906ebdc9:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebdcd:	c5 fe 7f 94 d6 90 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0x90],ymm2
    7f48906ebdd4:	00 00 
    7f48906ebdd6:	c5 fc 10 94 d6 b0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xb0]
    7f48906ebddd:	00 00 
    7f48906ebddf:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebde3:	c5 fe 7f 94 d6 b0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xb0],ymm2
    7f48906ebdea:	00 00 
    7f48906ebdec:	c5 fc 10 94 d6 d0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xd0]
    7f48906ebdf3:	00 00 
    7f48906ebdf5:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebdf9:	c5 fe 7f 94 d6 d0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xd0],ymm2
    7f48906ebe00:	00 00 
    7f48906ebe02:	c5 fc 10 94 d6 f0 00 	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0xf0]
    7f48906ebe09:	00 00 
    7f48906ebe0b:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebe0f:	c5 fe 7f 94 d6 f0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xf0],ymm2
    7f48906ebe16:	00 00 
    7f48906ebe18:	83 c2 20             	add    edx,0x20
    7f48906ebe1b:	41 3b d0             	cmp    edx,r8d
    7f48906ebe1e:	0f 8c 5c ff ff ff    	jl     0x7f48906ebd80
    7f48906ebe24:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ebe28:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ebe2b:	41 3b d3             	cmp    edx,r11d
    7f48906ebe2e:	0f 8c 1f ff ff ff    	jl     0x7f48906ebd53
    7f48906ebe34:	3b d1                	cmp    edx,ecx
    7f48906ebe36:	0f 8d de 06 00 00    	jge    0x7f48906ec51a
    7f48906ebe3c:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebe42:	c5 ed 59 d2          	vmulpd ymm2,ymm2,ymm2
    7f48906ebe46:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebe4c:	83 c2 04             	add    edx,0x4
    7f48906ebe4f:	3b d1                	cmp    edx,ecx
    7f48906ebe51:	7c e9                	jl     0x7f48906ebe3c
    7f48906ebe53:	e9 c2 06 00 00       	jmp    0x7f48906ec51a
    7f48906ebe58:	85 c9                	test   ecx,ecx
    7f48906ebe5a:	0f 8e ba 06 00 00    	jle    0x7f48906ec51a
    7f48906ebe60:	45 85 c9             	test   r9d,r9d
    7f48906ebe63:	0f 8c 53 07 00 00    	jl     0x7f48906ec5bc
    7f48906ebe69:	83 fb 03             	cmp    ebx,0x3
    7f48906ebe6c:	0f 84 4a 07 00 00    	je     0x7f48906ec5bc
    7f48906ebe72:	4d 3b ee             	cmp    r13,r14
    7f48906ebe75:	0f 83 41 07 00 00    	jae    0x7f48906ec5bc
    7f48906ebe7b:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ebe81:	0f 8f c5 08 00 00    	jg     0x7f48906ec74c
    7f48906ebe87:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906ebe8c:	c5 ed c2 dd 0e       	vcmpgtpd ymm3,ymm2,ymm5
    7f48906ebe91:	c4 e2 7d 19 35 f6 f6 	vbroadcastsd ymm6,QWORD PTR [rip+0xfffffffffffff6f6]        # 0x7f48906eb590
    7f48906ebe98:	ff ff 
    7f48906ebe9a:	c5 cd 59 e2          	vmulpd ymm4,ymm6,ymm2
    7f48906ebe9e:	c4 e3 5d 4a d2 30    	vblendvps ymm2,ymm4,ymm2,ymm3
    7f48906ebea4:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ebea9:	41 83 f8 04          	cmp    r8d,0x4
    7f48906ebead:	0f 8e b1 00 00 00    	jle    0x7f48906ebf64
    7f48906ebeb3:	44 8b d9             	mov    r11d,ecx
    7f48906ebeb6:	44 2b da             	sub    r11d,edx
    7f48906ebeb9:	41 83 c3 f4          	add    r11d,0xfffffff4
    7f48906ebebd:	45 33 c9             	xor    r9d,r9d
    7f48906ebec0:	44 3b c2             	cmp    r8d,edx
    7f48906ebec3:	45 0f 4c d9          	cmovl  r11d,r9d
    7f48906ebec7:	41 81 fb 80 3e 00 00 	cmp    r11d,0x3e80
    7f48906ebece:	44 0f 47 df          	cmova  r11d,edi
    7f48906ebed2:	44 03 da             	add    r11d,edx
    7f48906ebed5:	66 66 66 0f 1f 84 00 	data16 data16 nop WORD PTR [rax+rax*1+0x0]
    7f48906ebedc:	00 00 00 00 
    7f48906ebee0:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebee6:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebeea:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebeef:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebef5:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebefb:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ebf01:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebf05:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebf0a:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebf10:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ebf16:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ebf1c:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebf20:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebf25:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebf2b:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ebf31:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ebf37:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebf3b:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebf40:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebf46:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ebf4c:	83 c2 10             	add    edx,0x10
    7f48906ebf4f:	41 3b d3             	cmp    edx,r11d
    7f48906ebf52:	7c 8c                	jl     0x7f48906ebee0
    7f48906ebf54:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ebf58:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ebf5b:	41 3b d0             	cmp    edx,r8d
    7f48906ebf5e:	0f 8c 4f ff ff ff    	jl     0x7f48906ebeb3
    7f48906ebf64:	3b d1                	cmp    edx,ecx
    7f48906ebf66:	0f 8d ae 05 00 00    	jge    0x7f48906ec51a
    7f48906ebf6c:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ebf72:	c5 cd 59 da          	vmulpd ymm3,ymm6,ymm2
    7f48906ebf76:	c5 ed c2 e5 0e       	vcmpgtpd ymm4,ymm2,ymm5
    7f48906ebf7b:	c4 e3 65 4a d2 40    	vblendvps ymm2,ymm3,ymm2,ymm4
    7f48906ebf81:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ebf87:	83 c2 04             	add    edx,0x4
    7f48906ebf8a:	3b d1                	cmp    edx,ecx
    7f48906ebf8c:	7c de                	jl     0x7f48906ebf6c
    7f48906ebf8e:	e9 87 05 00 00       	jmp    0x7f48906ec51a
    7f48906ebf93:	85 c9                	test   ecx,ecx
    7f48906ebf95:	0f 8e 7f 05 00 00    	jle    0x7f48906ec51a
    7f48906ebf9b:	45 85 c9             	test   r9d,r9d
    7f48906ebf9e:	0f 8c f0 05 00 00    	jl     0x7f48906ec594
    7f48906ebfa4:	83 fb 03             	cmp    ebx,0x3
    7f48906ebfa7:	0f 84 e7 05 00 00    	je     0x7f48906ec594
    7f48906ebfad:	4d 3b ee             	cmp    r13,r14
    7f48906ebfb0:	0f 83 de 05 00 00    	jae    0x7f48906ec594
    7f48906ebfb6:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ebfbc:	0f 8f 62 07 00 00    	jg     0x7f48906ec724
    7f48906ebfc2:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906ebfc7:	c4 62 7d 19 15 c8 f5 	vbroadcastsd ymm10,QWORD PTR [rip+0xfffffffffffff5c8]        # 0x7f48906eb598
    7f48906ebfce:	ff ff 
    7f48906ebfd0:	c4 c1 6d c2 da 01    	vcmpltpd ymm3,ymm2,ymm10
    7f48906ebfd6:	c4 62 7d 19 1d c1 f5 	vbroadcastsd ymm11,QWORD PTR [rip+0xfffffffffffff5c1]        # 0x7f48906eb5a0
    7f48906ebfdd:	ff ff 
    7f48906ebfdf:	c4 e3 35 4b ea 90    	vblendvpd ymm5,ymm9,ymm2,ymm9
    7f48906ebfe5:	c4 c3 6d 4b e1 90    	vblendvpd ymm4,ymm2,ymm9,ymm9
    7f48906ebfeb:	c4 e1 d5 5d f4       	vminpd ymm6,ymm5,ymm4
    7f48906ebff0:	c5 d5 c2 e5 03       	vcmpunordpd ymm4,ymm5,ymm5
    7f48906ebff5:	c4 e3 4d 4b d5 40    	vblendvpd ymm2,ymm6,ymm5,ymm4
    7f48906ebffb:	c4 c3 6d 4a d2 30    	vblendvps ymm2,ymm2,ymm10,ymm3
    7f48906ec001:	c4 c1 6d 5e db       	vdivpd ymm3,ymm2,ymm11
    7f48906ec006:	c5 ed 59 e2          	vmulpd ymm4,ymm2,ymm2
    7f48906ec00a:	c5 dd 59 d2          	vmulpd ymm2,ymm4,ymm2
    7f48906ec00e:	c4 e2 7d 19 25 91 f5 	vbroadcastsd ymm4,QWORD PTR [rip+0xfffffffffffff591]        # 0x7f48906eb5a8
    7f48906ec015:	ff ff 
    7f48906ec017:	c5 ed 5e d4          	vdivpd ymm2,ymm2,ymm4
    7f48906ec01b:	c5 e5 5c d2          	vsubpd ymm2,ymm3,ymm2
    7f48906ec01f:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ec024:	41 83 f8 04          	cmp    r8d,0x4
    7f48906ec028:	0f 8e a1 01 00 00    	jle    0x7f48906ec1cf
    7f48906ec02e:	eb 0a                	jmp    0x7f48906ec03a
    7f48906ec030:	c4 c1 f9 6e c2       	vmovq  xmm0,r10
    7f48906ec035:	c4 c1 f9 6e cb       	vmovq  xmm1,r11
    7f48906ec03a:	8b d9                	mov    ebx,ecx
    7f48906ec03c:	2b da                	sub    ebx,edx
    7f48906ec03e:	83 c3 f4             	add    ebx,0xfffffff4
    7f48906ec041:	45 33 db             	xor    r11d,r11d
    7f48906ec044:	44 3b c2             	cmp    r8d,edx
    7f48906ec047:	41 0f 4c db          	cmovl  ebx,r11d
    7f48906ec04b:	81 fb 80 3e 00 00    	cmp    ebx,0x3e80
    7f48906ec051:	0f 47 df             	cmova  ebx,edi
    7f48906ec054:	03 da                	add    ebx,edx
    7f48906ec056:	c4 c1 f9 7e c2       	vmovq  r10,xmm0
    7f48906ec05b:	c4 c1 f9 7e cb       	vmovq  r11,xmm1
    7f48906ec060:	c5 fc 10 44 d6 10    	vmovups ymm0,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec066:	c4 c1 7d c2 ca 01    	vcmpltpd ymm1,ymm0,ymm10
    7f48906ec06c:	c4 63 35 4b f0 90    	vblendvpd ymm14,ymm9,ymm0,ymm9
    7f48906ec072:	c4 43 7d 4b f9 90    	vblendvpd ymm15,ymm0,ymm9,ymm9
    7f48906ec078:	c4 41 8d 5d ef       	vminpd ymm13,ymm14,ymm15
    7f48906ec07d:	c4 41 0d c2 fe 03    	vcmpunordpd ymm15,ymm14,ymm14
    7f48906ec083:	c4 c3 15 4b c6 f0    	vblendvpd ymm0,ymm13,ymm14,ymm15
    7f48906ec089:	c4 c3 7d 4a c2 10    	vblendvps ymm0,ymm0,ymm10,ymm1
    7f48906ec08f:	c4 41 7d 5e eb       	vdivpd ymm13,ymm0,ymm11
    7f48906ec094:	c5 fd 59 c8          	vmulpd ymm1,ymm0,ymm0
    7f48906ec098:	c5 f5 59 c0          	vmulpd ymm0,ymm1,ymm0
    7f48906ec09c:	c4 e2 7d 19 0d 03 f5 	vbroadcastsd ymm1,QWORD PTR [rip+0xfffffffffffff503]        # 0x7f48906eb5a8
    7f48906ec0a3:	ff ff 
    7f48906ec0a5:	c5 fd 5e c1          	vdivpd ymm0,ymm0,ymm1
    7f48906ec0a9:	c5 95 5c c0          	vsubpd ymm0,ymm13,ymm0
    7f48906ec0ad:	c5 fe 7f 44 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm0
    7f48906ec0b3:	c5 fc 10 44 d6 30    	vmovups ymm0,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ec0b9:	c4 c1 7d c2 ca 01    	vcmpltpd ymm1,ymm0,ymm10
    7f48906ec0bf:	c4 63 35 4b e0 90    	vblendvpd ymm12,ymm9,ymm0,ymm9
    7f48906ec0c5:	c4 c3 7d 4b d1 90    	vblendvpd ymm2,ymm0,ymm9,ymm9
    7f48906ec0cb:	c4 61 9d 5d ea       	vminpd ymm13,ymm12,ymm2
    7f48906ec0d0:	c4 c1 1d c2 d4 03    	vcmpunordpd ymm2,ymm12,ymm12
    7f48906ec0d6:	c4 c3 15 4b c4 20    	vblendvpd ymm0,ymm13,ymm12,ymm2
    7f48906ec0dc:	c4 c3 7d 4a c2 10    	vblendvps ymm0,ymm0,ymm10,ymm1
    7f48906ec0e2:	c4 c1 7d 5e d3       	vdivpd ymm2,ymm0,ymm11
    7f48906ec0e7:	c5 fd 59 c8          	vmulpd ymm1,ymm0,ymm0
    7f48906ec0eb:	c5 f5 59 c0          	vmulpd ymm0,ymm1,ymm0
    7f48906ec0ef:	c4 e2 7d 19 0d b0 f4 	vbroadcastsd ymm1,QWORD PTR [rip+0xfffffffffffff4b0]        # 0x7f48906eb5a8
    7f48906ec0f6:	ff ff 
    7f48906ec0f8:	c5 fd 5e c1          	vdivpd ymm0,ymm0,ymm1
    7f48906ec0fc:	c5 ed 5c c0          	vsubpd ymm0,ymm2,ymm0
    7f48906ec100:	c5 fe 7f 44 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm0
    7f48906ec106:	c5 fc 10 44 d6 50    	vmovups ymm0,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ec10c:	c4 e3 35 4b e0 90    	vblendvpd ymm4,ymm9,ymm0,ymm9
    7f48906ec112:	c4 c3 7d 4b e9 90    	vblendvpd ymm5,ymm0,ymm9,ymm9
    7f48906ec118:	c4 e1 dd 5d dd       	vminpd ymm3,ymm4,ymm5
    7f48906ec11d:	c5 dd c2 ec 03       	vcmpunordpd ymm5,ymm4,ymm4
    7f48906ec122:	c4 e3 65 4b cc 50    	vblendvpd ymm1,ymm3,ymm4,ymm5
    7f48906ec128:	c4 c1 7d c2 c2 01    	vcmpltpd ymm0,ymm0,ymm10
    7f48906ec12e:	c4 c3 75 4a c2 00    	vblendvps ymm0,ymm1,ymm10,ymm0
    7f48906ec134:	c4 c1 7d 5e cb       	vdivpd ymm1,ymm0,ymm11
    7f48906ec139:	c5 fd 59 d0          	vmulpd ymm2,ymm0,ymm0
    7f48906ec13d:	c5 ed 59 c0          	vmulpd ymm0,ymm2,ymm0
    7f48906ec141:	c4 e2 7d 19 15 5e f4 	vbroadcastsd ymm2,QWORD PTR [rip+0xfffffffffffff45e]        # 0x7f48906eb5a8
    7f48906ec148:	ff ff 
    7f48906ec14a:	c5 fd 5e c2          	vdivpd ymm0,ymm0,ymm2
    7f48906ec14e:	c5 f5 5c c0          	vsubpd ymm0,ymm1,ymm0
    7f48906ec152:	c5 fe 7f 44 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm0
    7f48906ec158:	c5 fc 10 44 d6 70    	vmovups ymm0,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ec15e:	c4 e3 35 4b f8 90    	vblendvpd ymm7,ymm9,ymm0,ymm9
    7f48906ec164:	c4 43 7d 4b c1 90    	vblendvpd ymm8,ymm0,ymm9,ymm9
    7f48906ec16a:	c4 c1 c5 5d f0       	vminpd ymm6,ymm7,ymm8
    7f48906ec16f:	c5 45 c2 c7 03       	vcmpunordpd ymm8,ymm7,ymm7
    7f48906ec174:	c4 e3 4d 4b cf 80    	vblendvpd ymm1,ymm6,ymm7,ymm8
    7f48906ec17a:	c4 c1 7d c2 c2 01    	vcmpltpd ymm0,ymm0,ymm10
    7f48906ec180:	c4 c3 75 4a c2 00    	vblendvps ymm0,ymm1,ymm10,ymm0
    7f48906ec186:	c4 c1 7d 5e cb       	vdivpd ymm1,ymm0,ymm11
    7f48906ec18b:	c5 fd 59 d0          	vmulpd ymm2,ymm0,ymm0
    7f48906ec18f:	c5 fd 59 c2          	vmulpd ymm0,ymm0,ymm2
    7f48906ec193:	c4 e2 7d 19 15 0c f4 	vbroadcastsd ymm2,QWORD PTR [rip+0xfffffffffffff40c]        # 0x7f48906eb5a8
    7f48906ec19a:	ff ff 
    7f48906ec19c:	c5 fd 5e c2          	vdivpd ymm0,ymm0,ymm2
    7f48906ec1a0:	c5 f5 5c c0          	vsubpd ymm0,ymm1,ymm0
    7f48906ec1a4:	c5 fe 7f 44 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm0
    7f48906ec1aa:	83 c2 10             	add    edx,0x10
    7f48906ec1ad:	3b d3                	cmp    edx,ebx
    7f48906ec1af:	0f 8c ab fe ff ff    	jl     0x7f48906ec060
    7f48906ec1b5:	4d 8b 4f 30          	mov    r9,QWORD PTR [r15+0x30]
    7f48906ec1b9:	41 85 01             	test   DWORD PTR [r9],eax
    7f48906ec1bc:	41 3b d0             	cmp    edx,r8d
    7f48906ec1bf:	0f 8c 6b fe ff ff    	jl     0x7f48906ec030
    7f48906ec1c5:	c4 c1 f9 6e c2       	vmovq  xmm0,r10
    7f48906ec1ca:	c4 c1 f9 6e cb       	vmovq  xmm1,r11
    7f48906ec1cf:	3b d1                	cmp    edx,ecx
    7f48906ec1d1:	0f 8d 43 03 00 00    	jge    0x7f48906ec51a
    7f48906ec1d7:	90                   	nop
    7f48906ec1d8:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec1de:	c4 c1 6d c2 da 01    	vcmpltpd ymm3,ymm2,ymm10
    7f48906ec1e4:	c4 e3 35 4b f2 90    	vblendvpd ymm6,ymm9,ymm2,ymm9
    7f48906ec1ea:	c4 c3 6d 4b e1 90    	vblendvpd ymm4,ymm2,ymm9,ymm9
    7f48906ec1f0:	c4 e1 cd 5d ec       	vminpd ymm5,ymm6,ymm4
    7f48906ec1f5:	c5 cd c2 e6 03       	vcmpunordpd ymm4,ymm6,ymm6
    7f48906ec1fa:	c4 e3 55 4b d6 40    	vblendvpd ymm2,ymm5,ymm6,ymm4
    7f48906ec200:	c4 c3 6d 4a d2 30    	vblendvps ymm2,ymm2,ymm10,ymm3
    7f48906ec206:	c4 c1 6d 5e db       	vdivpd ymm3,ymm2,ymm11
    7f48906ec20b:	c5 ed 59 e2          	vmulpd ymm4,ymm2,ymm2
    7f48906ec20f:	c5 dd 59 d2          	vmulpd ymm2,ymm4,ymm2
    7f48906ec213:	c4 e2 7d 19 25 8c f3 	vbroadcastsd ymm4,QWORD PTR [rip+0xfffffffffffff38c]        # 0x7f48906eb5a8
    7f48906ec21a:	ff ff 
    7f48906ec21c:	c5 ed 5e d4          	vdivpd ymm2,ymm2,ymm4
    7f48906ec220:	c5 e5 5c d2          	vsubpd ymm2,ymm3,ymm2
    7f48906ec224:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ec22a:	83 c2 04             	add    edx,0x4
    7f48906ec22d:	3b d1                	cmp    edx,ecx
    7f48906ec22f:	7c a7                	jl     0x7f48906ec1d8
    7f48906ec231:	e9 e4 02 00 00       	jmp    0x7f48906ec51a
    7f48906ec236:	85 c9                	test   ecx,ecx
    7f48906ec238:	0f 8e dc 02 00 00    	jle    0x7f48906ec51a
    7f48906ec23e:	45 85 c9             	test   r9d,r9d
    7f48906ec241:	0f 8c 25 03 00 00    	jl     0x7f48906ec56c
    7f48906ec247:	83 fb 03             	cmp    ebx,0x3
    7f48906ec24a:	0f 84 1c 03 00 00    	je     0x7f48906ec56c
    7f48906ec250:	4d 3b ee             	cmp    r13,r14
    7f48906ec253:	0f 83 13 03 00 00    	jae    0x7f48906ec56c
    7f48906ec259:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ec25f:	0f 8f 97 04 00 00    	jg     0x7f48906ec6fc
    7f48906ec265:	c5 dd 59 56 10       	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+0x10]
    7f48906ec26a:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ec26f:	41 83 fb 04          	cmp    r11d,0x4
    7f48906ec273:	0f 8e bb 00 00 00    	jle    0x7f48906ec334
    7f48906ec279:	44 8b d1             	mov    r10d,ecx
    7f48906ec27c:	44 2b d2             	sub    r10d,edx
    7f48906ec27f:	41 83 c2 e4          	add    r10d,0xffffffe4
    7f48906ec283:	45 33 c9             	xor    r9d,r9d
    7f48906ec286:	44 3b da             	cmp    r11d,edx
    7f48906ec289:	45 0f 4c d1          	cmovl  r10d,r9d
    7f48906ec28d:	41 81 fa 00 7d 00 00 	cmp    r10d,0x7d00
    7f48906ec294:	44 0f 47 d0          	cmova  r10d,eax
    7f48906ec298:	44 03 d2             	add    r10d,edx
    7f48906ec29b:	0f 1f 44 00 00       	nop    DWORD PTR [rax+rax*1+0x0]
    7f48906ec2a0:	c5 dd 59 54 d6 10    	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec2a6:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ec2ac:	c5 dd 59 54 d6 30    	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ec2b2:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ec2b8:	c5 dd 59 54 d6 50    	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ec2be:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ec2c4:	c5 dd 59 54 d6 70    	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ec2ca:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ec2d0:	c5 dd 59 94 d6 90 00 	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x90]
    7f48906ec2d7:	00 00 
    7f48906ec2d9:	c5 fe 7f 94 d6 90 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0x90],ymm2
    7f48906ec2e0:	00 00 
    7f48906ec2e2:	c5 dd 59 94 d6 b0 00 	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xb0]
    7f48906ec2e9:	00 00 
    7f48906ec2eb:	c5 fe 7f 94 d6 b0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xb0],ymm2
    7f48906ec2f2:	00 00 
    7f48906ec2f4:	c5 dd 59 94 d6 d0 00 	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xd0]
    7f48906ec2fb:	00 00 
    7f48906ec2fd:	c5 fe 7f 94 d6 d0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xd0],ymm2
    7f48906ec304:	00 00 
    7f48906ec306:	c5 dd 59 94 d6 f0 00 	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0xf0]
    7f48906ec30d:	00 00 
    7f48906ec30f:	c5 fe 7f 94 d6 f0 00 	vmovdqu YMMWORD PTR [rsi+rdx*8+0xf0],ymm2
    7f48906ec316:	00 00 
    7f48906ec318:	83 c2 20             	add    edx,0x20
    7f48906ec31b:	41 3b d2             	cmp    edx,r10d
    7f48906ec31e:	0f 8c 7c ff ff ff    	jl     0x7f48906ec2a0
    7f48906ec324:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ec328:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ec32b:	41 3b d3             	cmp    edx,r11d
    7f48906ec32e:	0f 8c 45 ff ff ff    	jl     0x7f48906ec279
    7f48906ec334:	3b d1                	cmp    edx,ecx
    7f48906ec336:	0f 8d de 01 00 00    	jge    0x7f48906ec51a
    7f48906ec33c:	c5 dd 59 54 d6 10    	vmulpd ymm2,ymm4,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec342:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ec348:	83 c2 04             	add    edx,0x4
    7f48906ec34b:	3b d1                	cmp    edx,ecx
    7f48906ec34d:	7c ed                	jl     0x7f48906ec33c
    7f48906ec34f:	e9 c6 01 00 00       	jmp    0x7f48906ec51a
    7f48906ec354:	85 c9                	test   ecx,ecx
    7f48906ec356:	0f 8e be 01 00 00    	jle    0x7f48906ec51a
    7f48906ec35c:	45 85 c9             	test   r9d,r9d
    7f48906ec35f:	0f 8c de 01 00 00    	jl     0x7f48906ec543
    7f48906ec365:	83 fb 03             	cmp    ebx,0x3
    7f48906ec368:	0f 84 d5 01 00 00    	je     0x7f48906ec543
    7f48906ec36e:	4d 3b ee             	cmp    r13,r14
    7f48906ec371:	0f 83 cc 01 00 00    	jae    0x7f48906ec543
    7f48906ec377:	81 f9 fc ff ff 7f    	cmp    ecx,0x7ffffffc
    7f48906ec37d:	0f 8f 51 03 00 00    	jg     0x7f48906ec6d4
    7f48906ec383:	c5 fc 10 56 10       	vmovups ymm2,YMMWORD PTR [rsi+0x10]
    7f48906ec388:	c5 ed c2 dc 01       	vcmpltpd ymm3,ymm2,ymm4
    7f48906ec38d:	c4 62 7d 19 c1       	vbroadcastsd ymm8,xmm1
    7f48906ec392:	c4 e3 3d 4b f2 80    	vblendvpd ymm6,ymm8,ymm2,ymm8
    7f48906ec398:	c4 c3 6d 4b e8 80    	vblendvpd ymm5,ymm2,ymm8,ymm8
    7f48906ec39e:	c4 e1 cd 5d fd       	vminpd ymm7,ymm6,ymm5
    7f48906ec3a3:	c5 cd c2 ee 03       	vcmpunordpd ymm5,ymm6,ymm6
    7f48906ec3a8:	c4 e3 45 4b d6 50    	vblendvpd ymm2,ymm7,ymm6,ymm5
    7f48906ec3ae:	c4 e3 6d 4a d4 30    	vblendvps ymm2,ymm2,ymm4,ymm3
    7f48906ec3b4:	c5 fe 7f 56 10       	vmovdqu YMMWORD PTR [rsi+0x10],ymm2
    7f48906ec3b9:	41 83 f8 04          	cmp    r8d,0x4
    7f48906ec3bd:	0f 8e 17 01 00 00    	jle    0x7f48906ec4da
    7f48906ec3c3:	44 8b d1             	mov    r10d,ecx
    7f48906ec3c6:	44 2b d2             	sub    r10d,edx
    7f48906ec3c9:	41 83 c2 f4          	add    r10d,0xfffffff4
    7f48906ec3cd:	45 33 c9             	xor    r9d,r9d
    7f48906ec3d0:	44 3b c2             	cmp    r8d,edx
    7f48906ec3d3:	45 0f 4c d1          	cmovl  r10d,r9d
    7f48906ec3d7:	41 81 fa 80 3e 00 00 	cmp    r10d,0x3e80
    7f48906ec3de:	44 0f 47 d7          	cmova  r10d,edi
    7f48906ec3e2:	44 03 d2             	add    r10d,edx
    7f48906ec3e5:	66 66 66 0f 1f 84 00 	data16 data16 nop WORD PTR [rax+rax*1+0x0]
    7f48906ec3ec:	00 00 00 00 
    7f48906ec3f0:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec3f6:	c5 ed c2 dc 01       	vcmpltpd ymm3,ymm2,ymm4
    7f48906ec3fb:	c4 e3 3d 4b f2 80    	vblendvpd ymm6,ymm8,ymm2,ymm8
    7f48906ec401:	c4 c3 6d 4b f8 80    	vblendvpd ymm7,ymm2,ymm8,ymm8
    7f48906ec407:	c4 e1 cd 5d ef       	vminpd ymm5,ymm6,ymm7
    7f48906ec40c:	c5 cd c2 fe 03       	vcmpunordpd ymm7,ymm6,ymm6
    7f48906ec411:	c4 e3 55 4b d6 70    	vblendvpd ymm2,ymm5,ymm6,ymm7
    7f48906ec417:	c4 e3 6d 4a d4 30    	vblendvps ymm2,ymm2,ymm4,ymm3
    7f48906ec41d:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ec423:	c5 fc 10 54 d6 30    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x30]
    7f48906ec429:	c5 ed c2 dc 01       	vcmpltpd ymm3,ymm2,ymm4
    7f48906ec42e:	c4 e3 3d 4b f2 80    	vblendvpd ymm6,ymm8,ymm2,ymm8
    7f48906ec434:	c4 c3 6d 4b f8 80    	vblendvpd ymm7,ymm2,ymm8,ymm8
    7f48906ec43a:	c4 e1 cd 5d ef       	vminpd ymm5,ymm6,ymm7
    7f48906ec43f:	c5 cd c2 fe 03       	vcmpunordpd ymm7,ymm6,ymm6
    7f48906ec444:	c4 e3 55 4b d6 70    	vblendvpd ymm2,ymm5,ymm6,ymm7
    7f48906ec44a:	c4 e3 6d 4a d4 30    	vblendvps ymm2,ymm2,ymm4,ymm3
    7f48906ec450:	c5 fe 7f 54 d6 30    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x30],ymm2
    7f48906ec456:	c5 fc 10 54 d6 50    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x50]
    7f48906ec45c:	c5 ed c2 dc 01       	vcmpltpd ymm3,ymm2,ymm4
    7f48906ec461:	c4 63 3d 4b ea 80    	vblendvpd ymm13,ymm8,ymm2,ymm8
    7f48906ec467:	c4 43 6d 4b c8 80    	vblendvpd ymm9,ymm2,ymm8,ymm8
    7f48906ec46d:	c4 c1 95 5d e9       	vminpd ymm5,ymm13,ymm9
    7f48906ec472:	c4 41 15 c2 cd 03    	vcmpunordpd ymm9,ymm13,ymm13
    7f48906ec478:	c4 c3 55 4b d5 90    	vblendvpd ymm2,ymm5,ymm13,ymm9
    7f48906ec47e:	c4 e3 6d 4a d4 30    	vblendvps ymm2,ymm2,ymm4,ymm3
    7f48906ec484:	c5 fe 7f 54 d6 50    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x50],ymm2
    7f48906ec48a:	c5 fc 10 54 d6 70    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x70]
    7f48906ec490:	c4 63 3d 4b e2 80    	vblendvpd ymm12,ymm8,ymm2,ymm8
    7f48906ec496:	c4 43 6d 4b d8 80    	vblendvpd ymm11,ymm2,ymm8,ymm8
    7f48906ec49c:	c4 41 9d 5d d3       	vminpd ymm10,ymm12,ymm11
    7f48906ec4a1:	c4 41 1d c2 dc 03    	vcmpunordpd ymm11,ymm12,ymm12
    7f48906ec4a7:	c4 c3 2d 4b dc b0    	vblendvpd ymm3,ymm10,ymm12,ymm11
    7f48906ec4ad:	c5 ed c2 d4 01       	vcmpltpd ymm2,ymm2,ymm4
    7f48906ec4b2:	c4 e3 65 4a d4 20    	vblendvps ymm2,ymm3,ymm4,ymm2
    7f48906ec4b8:	c5 fe 7f 54 d6 70    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x70],ymm2
    7f48906ec4be:	83 c2 10             	add    edx,0x10
    7f48906ec4c1:	41 3b d2             	cmp    edx,r10d
    7f48906ec4c4:	0f 8c 26 ff ff ff    	jl     0x7f48906ec3f0
    7f48906ec4ca:	4d 8b 57 30          	mov    r10,QWORD PTR [r15+0x30]
    7f48906ec4ce:	41 85 02             	test   DWORD PTR [r10],eax
    7f48906ec4d1:	41 3b d0             	cmp    edx,r8d
    7f48906ec4d4:	0f 8c e9 fe ff ff    	jl     0x7f48906ec3c3
    7f48906ec4da:	3b d1                	cmp    edx,ecx
    7f48906ec4dc:	7d 3c                	jge    0x7f48906ec51a
    7f48906ec4de:	66 90                	xchg   ax,ax
    7f48906ec4e0:	c5 fc 10 54 d6 10    	vmovups ymm2,YMMWORD PTR [rsi+rdx*8+0x10]
    7f48906ec4e6:	c5 ed c2 dc 01       	vcmpltpd ymm3,ymm2,ymm4
    7f48906ec4eb:	c4 e3 3d 4b fa 80    	vblendvpd ymm7,ymm8,ymm2,ymm8
    7f48906ec4f1:	c4 c3 6d 4b e8 80    	vblendvpd ymm5,ymm2,ymm8,ymm8
    7f48906ec4f7:	c4 e1 c5 5d f5       	vminpd ymm6,ymm7,ymm5
    7f48906ec4fc:	c5 c5 c2 ef 03       	vcmpunordpd ymm5,ymm7,ymm7
    7f48906ec501:	c4 e3 4d 4b d7 50    	vblendvpd ymm2,ymm6,ymm7,ymm5
    7f48906ec507:	c4 e3 6d 4a d4 30    	vblendvps ymm2,ymm2,ymm4,ymm3
    7f48906ec50d:	c5 fe 7f 54 d6 10    	vmovdqu YMMWORD PTR [rsi+rdx*8+0x10],ymm2
    7f48906ec513:	83 c2 04             	add    edx,0x4
    7f48906ec516:	3b d1                	cmp    edx,ecx
    7f48906ec518:	7c c6                	jl     0x7f48906ec4e0
    7f48906ec51a:	48 8b 14 24          	mov    rdx,QWORD PTR [rsp]
    7f48906ec51e:	66 90                	xchg   ax,ax
    7f48906ec520:	c5 f8 77             	vzeroupper
    7f48906ec523:	e8 b8 27 f6 ff       	call   0x7f489064ece0
    7f48906ec528:	0f 1f 84 00 a0 10 00 	nop    DWORD PTR [rax+rax*1+0xc0010a0]
    7f48906ec52f:	0c 
    7f48906ec530:	c5 f8 77             	vzeroupper
    7f48906ec533:	48 83 c4 40          	add    rsp,0x40
    7f48906ec537:	5d                   	pop    rbp
    7f48906ec538:	49 3b 67 28          	cmp    rsp,QWORD PTR [r15+0x28]
    7f48906ec53c:	0f 87 62 03 00 00    	ja     0x7f48906ec8a4
    7f48906ec542:	c3                   	ret
    7f48906ec543:	48 8b ee             	mov    rbp,rsi
    7f48906ec546:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec54c:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec552:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec556:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec55b:	90                   	nop
    7f48906ec55c:	c5 f8 77             	vzeroupper
    7f48906ec55f:	e8 7c 33 fa ff       	call   0x7f489068f8e0
    7f48906ec564:	0f 1f 84 00 dc 10 00 	nop    DWORD PTR [rax+rax*1+0xd0010dc]
    7f48906ec56b:	0d 
    7f48906ec56c:	48 8b ee             	mov    rbp,rsi
    7f48906ec56f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec575:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec57b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec57f:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec584:	c5 f8 77             	vzeroupper
    7f48906ec587:	e8 54 33 fa ff       	call   0x7f489068f8e0
    7f48906ec58c:	0f 1f 84 00 04 11 00 	nop    DWORD PTR [rax+rax*1+0xe001104]
    7f48906ec593:	0e 
    7f48906ec594:	48 8b ee             	mov    rbp,rsi
    7f48906ec597:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec59d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec5a3:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec5a7:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec5ac:	c5 f8 77             	vzeroupper
    7f48906ec5af:	e8 2c 33 fa ff       	call   0x7f489068f8e0
    7f48906ec5b4:	0f 1f 84 00 2c 11 00 	nop    DWORD PTR [rax+rax*1+0xf00112c]
    7f48906ec5bb:	0f 
    7f48906ec5bc:	48 8b ee             	mov    rbp,rsi
    7f48906ec5bf:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec5c5:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec5cb:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec5cf:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec5d4:	c5 f8 77             	vzeroupper
    7f48906ec5d7:	e8 04 33 fa ff       	call   0x7f489068f8e0
    7f48906ec5dc:	0f 1f 84 00 54 11 00 	nop    DWORD PTR [rax+rax*1+0x10001154]
    7f48906ec5e3:	10 
    7f48906ec5e4:	48 8b ee             	mov    rbp,rsi
    7f48906ec5e7:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec5ed:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec5f3:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec5f7:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec5fc:	c5 f8 77             	vzeroupper
    7f48906ec5ff:	e8 dc 32 fa ff       	call   0x7f489068f8e0
    7f48906ec604:	0f 1f 84 00 7c 11 00 	nop    DWORD PTR [rax+rax*1+0x1100117c]
    7f48906ec60b:	11 
    7f48906ec60c:	48 8b ee             	mov    rbp,rsi
    7f48906ec60f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec615:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec61b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec61f:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec624:	c5 f8 77             	vzeroupper
    7f48906ec627:	e8 b4 32 fa ff       	call   0x7f489068f8e0
    7f48906ec62c:	0f 1f 84 00 a4 11 00 	nop    DWORD PTR [rax+rax*1+0x120011a4]
    7f48906ec633:	12 
    7f48906ec634:	48 8b ee             	mov    rbp,rsi
    7f48906ec637:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec63d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec643:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec647:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec64c:	c5 f8 77             	vzeroupper
    7f48906ec64f:	e8 8c 32 fa ff       	call   0x7f489068f8e0
    7f48906ec654:	0f 1f 84 00 cc 11 00 	nop    DWORD PTR [rax+rax*1+0x130011cc]
    7f48906ec65b:	13 
    7f48906ec65c:	48 8b ee             	mov    rbp,rsi
    7f48906ec65f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec665:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec66b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec66f:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec674:	c5 f8 77             	vzeroupper
    7f48906ec677:	e8 64 32 fa ff       	call   0x7f489068f8e0
    7f48906ec67c:	0f 1f 84 00 f4 11 00 	nop    DWORD PTR [rax+rax*1+0x140011f4]
    7f48906ec683:	14 
    7f48906ec684:	48 8b ee             	mov    rbp,rsi
    7f48906ec687:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec68d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec693:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec697:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec69c:	c5 f8 77             	vzeroupper
    7f48906ec69f:	e8 3c 32 fa ff       	call   0x7f489068f8e0
    7f48906ec6a4:	0f 1f 84 00 1c 12 00 	nop    DWORD PTR [rax+rax*1+0x1500121c]
    7f48906ec6ab:	15 
    7f48906ec6ac:	48 8b ee             	mov    rbp,rsi
    7f48906ec6af:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec6b5:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec6bb:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec6bf:	be 6e ff ff ff       	mov    esi,0xffffff6e
    7f48906ec6c4:	c5 f8 77             	vzeroupper
    7f48906ec6c7:	e8 14 32 fa ff       	call   0x7f489068f8e0
    7f48906ec6cc:	0f 1f 84 00 44 12 00 	nop    DWORD PTR [rax+rax*1+0x16001244]
    7f48906ec6d3:	16 
    7f48906ec6d4:	48 8b ee             	mov    rbp,rsi
    7f48906ec6d7:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec6dd:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec6e3:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec6e7:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec6ec:	c5 f8 77             	vzeroupper
    7f48906ec6ef:	e8 ec 31 fa ff       	call   0x7f489068f8e0
    7f48906ec6f4:	0f 1f 84 00 6c 12 00 	nop    DWORD PTR [rax+rax*1+0x1700126c]
    7f48906ec6fb:	17 
    7f48906ec6fc:	48 8b ee             	mov    rbp,rsi
    7f48906ec6ff:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec705:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec70b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec70f:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec714:	c5 f8 77             	vzeroupper
    7f48906ec717:	e8 c4 31 fa ff       	call   0x7f489068f8e0
    7f48906ec71c:	0f 1f 84 00 94 12 00 	nop    DWORD PTR [rax+rax*1+0x18001294]
    7f48906ec723:	18 
    7f48906ec724:	48 8b ee             	mov    rbp,rsi
    7f48906ec727:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec72d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec733:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec737:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec73c:	c5 f8 77             	vzeroupper
    7f48906ec73f:	e8 9c 31 fa ff       	call   0x7f489068f8e0
    7f48906ec744:	0f 1f 84 00 bc 12 00 	nop    DWORD PTR [rax+rax*1+0x190012bc]
    7f48906ec74b:	19 
    7f48906ec74c:	48 8b ee             	mov    rbp,rsi
    7f48906ec74f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec755:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec75b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec75f:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec764:	c5 f8 77             	vzeroupper
    7f48906ec767:	e8 74 31 fa ff       	call   0x7f489068f8e0
    7f48906ec76c:	0f 1f 84 00 e4 12 00 	nop    DWORD PTR [rax+rax*1+0x1a0012e4]
    7f48906ec773:	1a 
    7f48906ec774:	48 8b ee             	mov    rbp,rsi
    7f48906ec777:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec77d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec783:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec787:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec78c:	c5 f8 77             	vzeroupper
    7f48906ec78f:	e8 4c 31 fa ff       	call   0x7f489068f8e0
    7f48906ec794:	0f 1f 84 00 0c 13 00 	nop    DWORD PTR [rax+rax*1+0x1b00130c]
    7f48906ec79b:	1b 
    7f48906ec79c:	48 8b ee             	mov    rbp,rsi
    7f48906ec79f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec7a5:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec7ab:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec7af:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec7b4:	c5 f8 77             	vzeroupper
    7f48906ec7b7:	e8 24 31 fa ff       	call   0x7f489068f8e0
    7f48906ec7bc:	0f 1f 84 00 34 13 00 	nop    DWORD PTR [rax+rax*1+0x1c001334]
    7f48906ec7c3:	1c 
    7f48906ec7c4:	48 8b ee             	mov    rbp,rsi
    7f48906ec7c7:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec7cd:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec7d3:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec7d7:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec7dc:	c5 f8 77             	vzeroupper
    7f48906ec7df:	e8 fc 30 fa ff       	call   0x7f489068f8e0
    7f48906ec7e4:	0f 1f 84 00 5c 13 00 	nop    DWORD PTR [rax+rax*1+0x1d00135c]
    7f48906ec7eb:	1d 
    7f48906ec7ec:	48 8b ee             	mov    rbp,rsi
    7f48906ec7ef:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec7f5:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec7fb:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec7ff:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec804:	c5 f8 77             	vzeroupper
    7f48906ec807:	e8 d4 30 fa ff       	call   0x7f489068f8e0
    7f48906ec80c:	0f 1f 84 00 84 13 00 	nop    DWORD PTR [rax+rax*1+0x1e001384]
    7f48906ec813:	1e 
    7f48906ec814:	48 8b ee             	mov    rbp,rsi
    7f48906ec817:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec81d:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec823:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec827:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec82c:	c5 f8 77             	vzeroupper
    7f48906ec82f:	e8 ac 30 fa ff       	call   0x7f489068f8e0
    7f48906ec834:	0f 1f 84 00 ac 13 00 	nop    DWORD PTR [rax+rax*1+0x1f0013ac]
    7f48906ec83b:	1f 
    7f48906ec83c:	48 8b ee             	mov    rbp,rsi
    7f48906ec83f:	c5 fb 11 44 24 08    	vmovsd QWORD PTR [rsp+0x8],xmm0
    7f48906ec845:	c5 fb 11 4c 24 10    	vmovsd QWORD PTR [rsp+0x10],xmm1
    7f48906ec84b:	89 4c 24 1c          	mov    DWORD PTR [rsp+0x1c],ecx
    7f48906ec84f:	be 66 ff ff ff       	mov    esi,0xffffff66
    7f48906ec854:	c5 f8 77             	vzeroupper
    7f48906ec857:	e8 84 30 fa ff       	call   0x7f489068f8e0
    7f48906ec85c:	0f 1f 84 00 d4 13 00 	nop    DWORD PTR [rax+rax*1+0x200013d4]
    7f48906ec863:	20 
    7f48906ec864:	48 8b f0             	mov    rsi,rax
    7f48906ec867:	c5 f8 77             	vzeroupper
    7f48906ec86a:	48 83 c4 40          	add    rsp,0x40
    7f48906ec86e:	5d                   	pop    rbp
    7f48906ec86f:	e9 6c d6 fa ff       	jmp    0x7f4890699ee0
    7f48906ec874:	be f6 ff ff ff       	mov    esi,0xfffffff6
    7f48906ec879:	66 66 90             	data16 xchg ax,ax
    7f48906ec87c:	c5 f8 77             	vzeroupper
    7f48906ec87f:	e8 5c 30 fa ff       	call   0x7f489068f8e0
    7f48906ec884:	0f 1f 84 00 fc 13 00 	nop    DWORD PTR [rax+rax*1+0x210013fc]
    7f48906ec88b:	21 
    7f48906ec88c:	be f6 ff ff ff       	mov    esi,0xfffffff6
    7f48906ec891:	66 66 90             	data16 xchg ax,ax
    7f48906ec894:	c5 f8 77             	vzeroupper
    7f48906ec897:	e8 44 30 fa ff       	call   0x7f489068f8e0
    7f48906ec89c:	0f 1f 84 00 14 14 00 	nop    DWORD PTR [rax+rax*1+0x22001414]
    7f48906ec8a3:	22 
    7f48906ec8a4:	49 ba 38 c5 6e 90 48 	movabs r10,0x7f48906ec538
    7f48906ec8ab:	7f 00 00 
    7f48906ec8ae:	4d 89 97 38 05 00 00 	mov    QWORD PTR [r15+0x538],r10
    7f48906ec8b5:	e9 a6 3f f6 ff       	jmp    0x7f4890650860
    7f48906ec8ba:	e8 a1 96 f4 ff       	call   0x7f4890635f60
    7f48906ec8bf:	e9 56 ed ff ff       	jmp    0x7f48906eb61a
    7f48906ec8c4:	f4                   	hlt
    7f48906ec8c5:	f4                   	hlt
    7f48906ec8c6:	f4                   	hlt
    7f48906ec8c7:	f4                   	hlt
