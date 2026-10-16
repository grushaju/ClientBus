import { useEffect } from 'react'

interface ImageViewerProps {
    src: string
    alt: string
    onClose: () => void
}

function ImageViewer({
                         src,
                         alt,
                         onClose
                     }: ImageViewerProps) {
    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                onClose()
            }
        }

        document.addEventListener(
            'keydown',
            handleKeyDown
        )

        return () => {
            document.removeEventListener(
                'keydown',
                handleKeyDown
            )
        }
    }, [onClose])

    return (
        <div
            className="image-viewer"
            role="dialog"
            aria-modal="true"
            aria-label="Просмотр изображения"
            onClick={onClose}
        >
            <button
                type="button"
                className="image-viewer-close"
                aria-label="Закрыть"
                onClick={onClose}
            >
                ×
            </button>

            <img
                className="image-viewer-image"
                src={src}
                alt={alt}
                onClick={event => {
                    event.stopPropagation()
                }}
            />
        </div>
    )
}

export default ImageViewer